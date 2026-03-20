package com.urcm.abap.exporter.handlers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.sap.adt.tools.core.project.IAbapProject;


/**
 * Eclipse plugin handler that exports ABAP package source code to local files.
 *
 * Uses the SAP ADT SDK's IRestResource API via reflection to make authenticated
 * HTTP calls. Authentication is handled automatically by the ADT session
 * (browser-based BTP OAuth or on-premise SAP Logon).
 *
 * The key challenge: our plugin cannot directly import IRestResource or related
 * ADT communication classes because they are internal to SAP's OSGi bundles.
 * We therefore use reflection exclusively for the HTTP layer, while importing
 * the public IAbapProject adapter directly.
 */
public class ExportHandler extends AbstractHandler {

    private static final Map<String, String> FOLDER_MAP = new HashMap<>();
    private static final Map<String, String> EXT_MAP    = new HashMap<>();

    static {
        FOLDER_MAP.put("CLAS", "clas");   FOLDER_MAP.put("INTF", "intf");
        FOLDER_MAP.put("DDLS", "ddls");   FOLDER_MAP.put("DDLX", "ddlx");
        FOLDER_MAP.put("BDEF", "bdef");   FOLDER_MAP.put("SRVD", "srvd");
        FOLDER_MAP.put("SRVB", "srvb");   FOLDER_MAP.put("DCLS", "dcls");
        FOLDER_MAP.put("PROG", "prog");   FOLDER_MAP.put("FUGR", "fugr");
        FOLDER_MAP.put("TABL", "tabl");   FOLDER_MAP.put("TTYP", "ttyp");
        FOLDER_MAP.put("DTEL", "dtel");   FOLDER_MAP.put("DOMA", "doma");
        FOLDER_MAP.put("MSAG", "msag");   FOLDER_MAP.put("DEVC", "devc");
        FOLDER_MAP.put("ENHO", "enho");   FOLDER_MAP.put("ENHS", "enhs");
        FOLDER_MAP.put("SMIM", "smim");   FOLDER_MAP.put("XSLT", "xslt");

        EXT_MAP.put("CLAS", ".clas.abap"); EXT_MAP.put("INTF", ".intf.abap");
        EXT_MAP.put("DDLS", ".asddls");    EXT_MAP.put("DDLX", ".asddlxs");
        EXT_MAP.put("BDEF", ".asbdef");    EXT_MAP.put("SRVD", ".asrvd");
        EXT_MAP.put("SRVB", ".asrvb");     EXT_MAP.put("DCLS", ".asdcls");
        EXT_MAP.put("PROG", ".prog.abap"); EXT_MAP.put("FUGR", ".fugr.abap");
        EXT_MAP.put("TABL", ".tabl.asx");  EXT_MAP.put("TTYP", ".ttyp.asx");
        EXT_MAP.put("DTEL", ".dtel.asx");  EXT_MAP.put("DOMA", ".doma.asx");
        EXT_MAP.put("MSAG", ".msag.asx");
    }

    // Object types that do NOT have a /source/main endpoint
    private static final Set<String> SKIP_SOURCE_TYPES = new HashSet<>();
    static {
        // SAP service/config objects
        SKIP_SOURCE_TYPES.add("SRVB");  // Service Bindings
        SKIP_SOURCE_TYPES.add("SRVD");  // Service Definitions
        SKIP_SOURCE_TYPES.add("SRVC");  // Service Consumption Models
        SKIP_SOURCE_TYPES.add("MSAG");  // Message Classes
        SKIP_SOURCE_TYPES.add("SMIM");  // MIME Objects
        SKIP_SOURCE_TYPES.add("ENHS");  // Enhancement Spots
        SKIP_SOURCE_TYPES.add("ENHO");  // Enhancement Implementations
        SKIP_SOURCE_TYPES.add("XSLT");  // XSLT Programs
        SKIP_SOURCE_TYPES.add("NSPC");  // Namespaces
        SKIP_SOURCE_TYPES.add("NROB");  // Number Range Objects
        SKIP_SOURCE_TYPES.add("TOBJ");  // Lock Objects
        SKIP_SOURCE_TYPES.add("SUSH");  // Authorization Defaults
        SKIP_SOURCE_TYPES.add("SIA6");  // API Release States
        // OData/ICF service objects
        SKIP_SOURCE_TYPES.add("IWVB");  // OData V2/V4 Vocabularies
        SKIP_SOURCE_TYPES.add("IWOM");  // OData Model
        SKIP_SOURCE_TYPES.add("IWSG");  // OData Service Group
        SKIP_SOURCE_TYPES.add("IWSV");  // OData Service Version
        SKIP_SOURCE_TYPES.add("SCO2");  // Service Communication Object
        SKIP_SOURCE_TYPES.add("OA2S");  // OAuth2 Scope
        SKIP_SOURCE_TYPES.add("G4BA");  // OData V4 Business Service Adapter
        SKIP_SOURCE_TYPES.add("EVTB");  // Event Type Binding
    }

    // Cached ADT ClassLoader — resolved once from the factory singleton
    private ClassLoader adtCL;

    // Diagnostic log — written to ~/sap_export_debug.txt on every run
    private StringBuilder debugLog;
    private boolean methodsLoggedOnce = false;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);

        Object selectedElement = null;
        if (selection instanceof IStructuredSelection) {
            selectedElement = ((IStructuredSelection) selection).getFirstElement();
        }
        if (selectedElement == null) {
            MessageDialog.openError(shell, "Error", "Nothing selected.");
            return null;
        }

        // Resolve IProject from selection
        IProject project = null;
        if (selectedElement instanceof IProject) {
            project = (IProject) selectedElement;
        }
        if (project == null && selectedElement instanceof IAdaptable) {
            project = ((IAdaptable) selectedElement).getAdapter(IProject.class);
        }
        if (project == null) {
            try {
                Object p = selectedElement.getClass()
                        .getMethod("getProject").invoke(selectedElement);
                if (p instanceof IProject) project = (IProject) p;
            } catch (Exception e) { /* ignore */ }
        }
        if (project == null) {
            MessageDialog.openError(shell, "Error", "No project found.");
            return null;
        }

        // Get destination ID via the public IAbapProject adapter
        IAbapProject abapProject = project.getAdapter(IAbapProject.class);
        if (abapProject == null) {
            MessageDialog.openError(shell, "Error", "Not an ABAP project.");
            return null;
        }
        String destId = abapProject.getDestinationId();

        // Pre-fill package name from selected element
        String defaultPkg = project.getName();
        try {
            Object nameObj = selectedElement.getClass()
                    .getMethod("getName").invoke(selectedElement);
            if (nameObj instanceof String && !((String) nameObj).isEmpty()) {
                defaultPkg = (String) nameObj;
            }
        } catch (Exception e) { /* ignore */ }

        InputDialog pkgDialog = new InputDialog(shell, "Export ABAP Package",
                "Enter package name:", defaultPkg, null);
        if (pkgDialog.open() != Window.OK) return null;
        String packageName = pkgDialog.getValue().toUpperCase().trim();

        DirectoryDialog dirDialog = new DirectoryDialog(shell);
        dirDialog.setMessage("Select target directory");
        String targetDir = dirDialog.open();
        if (targetDir == null) return null;

        File baseDir = new File(targetDir, packageName.toLowerCase());
        if (!baseDir.exists()) baseDir.mkdirs();

        final List<String>  exported = new ArrayList<>();
        final List<String>  skipped  = new ArrayList<>();
        final StringBuilder log      = new StringBuilder();
        final String        pkgFinal = packageName;
        final String        destFinal = destId;

        try {
            BusyIndicator.showWhile(shell.getDisplay(), () -> {
                try {
                    recurse(pkgFinal, "", baseDir, destFinal, exported, skipped, log, 0);
                    log.insert(0, "Done!\nPackage : " + pkgFinal
                            + "\nExported: " + exported.size()
                            + "\nSkipped : " + skipped.size()
                            + "\nTotal   : " + (exported.size() + skipped.size())
                            + "\n\n");
                } catch (Exception e) {
                    log.append("ERROR: ").append(e.getMessage());
                }
            });

            String msg = log.toString();
            if (msg.length() > 5000) msg = msg.substring(0, 5000) + "\n...(truncated)";
            MessageDialog.openInformation(shell, "Export Result", msg);

        } catch (Exception e) {
            MessageDialog.openError(shell, "Export Failed", e.getMessage());
        }

        return null;
    }

    // =========================================================
    // ADT REST call via IRestResource — handles auth automatically
    //
    // Confirmed method signatures from diagnostic dump:
    //   post(IProgressMonitor, IHeaders, Class, IQueryParameter[])
    //   post(IProgressMonitor, Class, IQueryParameter[])
    //   get(IProgressMonitor, IHeaders, Class, IQueryParameter[])
    //   get(IProgressMonitor, Class, IQueryParameter[])
    //
    // We find the Method by matching parameter-type SIMPLE NAMES
    // to avoid ClassLoader boundary issues with isAssignableFrom().
    // =========================================================
    private String adtCall(String destId, String httpMethod, String path,
                           String query, String acceptHeader) throws Exception {
        String fullUri = path;
        if (query != null && !query.isEmpty()) fullUri += "?" + query;

        // Init diagnostic log
        if (debugLog == null) debugLog = new StringBuilder();
        debugLog.append("\n=== adtCall: ").append(httpMethod).append(" ").append(fullUri).append(" ===\n");

        // Step 1: get factory class and singleton instance
        Class<?> factoryClass = Class.forName(
                "com.sap.adt.communication.resources.AdtRestResourceFactory");
        if (!methodsLoggedOnce) logMethods("AdtRestResourceFactory", factoryClass);
        Object factory = findAndInvoke(factoryClass, null,
                "createRestResourceFactory", new String[0], new Object[0]);
        if (factory == null) {
            throw new Exception("AdtRestResourceFactory.createRestResourceFactory() returned null.");
        }

        // Cache the ADT ClassLoader from the factory
        if (adtCL == null) adtCL = factory.getClass().getClassLoader();
        if (!methodsLoggedOnce) logMethods("factory (" + factory.getClass().getSimpleName() + ")", factory.getClass());

        // Step 2: create IRestResource for the URI + destination
        //   Signature: createResourceWithStatelessSession(URI, String)
        Object restResource = findAndInvoke(factory.getClass(), factory,
                "createResourceWithStatelessSession",
                new String[]{ "URI", "String" },
                new Object[]{ java.net.URI.create(fullUri), destId });

        if (restResource == null) {
            throw new Exception("createResourceWithStatelessSession returned null for: " + fullUri);
        }
        if (!methodsLoggedOnce) {
            logMethods("restResource (" + restResource.getClass().getSimpleName() + ")", restResource.getClass());
            // Also log interfaces
            for (Class<?> iface : getAllInterfaces(restResource.getClass())) {
                logMethods("  interface: " + iface.getSimpleName(), iface);
            }
        }

        // Step 3: NullProgressMonitor — loaded from the Eclipse platform
        NullProgressMonitor monitor = new NullProgressMonitor();

        // Step 4: empty IQueryParameter[] — MUST be loaded via ADT ClassLoader
        Class<?> iqpClass = Class.forName(
                "com.sap.adt.communication.resources.IQueryParameter", true, adtCL);
        Object emptyQueryArr = Array.newInstance(iqpClass, 0);

        // Step 5: IResponse class token — loaded via ADT ClassLoader
        Class<?> iResponseClass = Class.forName(
                "com.sap.adt.communication.message.IResponse", true, adtCL);

        // Step 6: invoke get/post
        String method = httpMethod.toLowerCase();
        Object result;

        if (acceptHeader != null && !acceptHeader.isEmpty()) {
            // Build IHeaders with Accept field
            Object headers = buildHeaders(acceptHeader);

            // Target: post/get(IProgressMonitor, IHeaders, Class, IQueryParameter[])
            result = findAndInvoke(restResource.getClass(), restResource, method,
                    new String[]{ "IProgressMonitor", "IHeaders", "Class", "IQueryParameter[]" },
                    new Object[]{ monitor, headers, iResponseClass, emptyQueryArr });
        } else {
            // Target: post/get(IProgressMonitor, Class, IQueryParameter[])
            result = findAndInvoke(restResource.getClass(), restResource, method,
                    new String[]{ "IProgressMonitor", "Class", "IQueryParameter[]" },
                    new Object[]{ monitor, iResponseClass, emptyQueryArr });
        }

        // Write debug log to file (once)
        if (!methodsLoggedOnce) {
            flushDebugLog();
            methodsLoggedOnce = true;
        }

        // Step 7: extract body string from result
        return extractBody(result);
    }

    /**
     * Builds an IHeaders instance with a single Accept field via HeadersFactory.
     */
    private Object buildHeaders(String acceptValue) throws Exception {
        Class<?> hfClass = Class.forName(
                "com.sap.adt.communication.message.HeadersFactory", true, adtCL);

        // HeadersFactory.newHeaders()
        Object headers = findAndInvoke(hfClass, null, "newHeaders",
                new String[0], new Object[0]);
        if (headers == null) {
            throw new Exception("HeadersFactory.newHeaders() returned null.");
        }

        // HeadersFactory.newField(String name, String[] values)
        Object field = findAndInvoke(hfClass, null, "newField",
                new String[]{ "String", "String[]" },
                new Object[]{ "Accept", new String[]{ acceptValue } });
        if (field == null) {
            throw new Exception("HeadersFactory.newField() returned null.");
        }

        // headers.addField(field)
        findAndInvoke(headers.getClass(), headers, "addField",
                new String[]{ "IField" },
                new Object[]{ field });

        return headers;
    }

    /**
     * Extracts the response body as a String from an IResponse object.
     * IResponse.getBody() returns IMessageBody, which has getInputStream().
     */
    private String extractBody(Object result) throws Exception {
        if (result == null) return "";
        if (result instanceof String) return (String) result;

        if (!methodsLoggedOnce) logMethods("response (" + result.getClass().getSimpleName() + ")", result.getClass());

        // IResponse -> getBody() -> getInputStream()
        Object body = findAndInvoke(result.getClass(), result,
                "getBody", new String[0], new Object[0]);
        if (body == null) return "";

        if (!methodsLoggedOnce) logMethods("messageBody (" + body.getClass().getSimpleName() + ")", body.getClass());

        Object isObj = findAndInvoke(body.getClass(), body,
                "getContent", new String[0], new Object[0]);
        if (isObj == null) return "";

        InputStream is = (InputStream) isObj;
        try (java.util.Scanner sc = new java.util.Scanner(
                is, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return sc.hasNext() ? sc.next() : "";
        }
    }

    /**
     * Core reflection helper: finds a Method by matching parameter simple names
     * (avoiding ClassLoader boundary issues), then invokes it.
     *
     * Searches both the concrete class AND all its interfaces for the method.
     * This is critical because in OSGi, the interface and implementation may
     * be loaded by different ClassLoaders.
     *
     * @param clazz         The class to search methods on
     * @param target        The target object (null for static methods)
     * @param methodName    The method name to find
     * @param paramPattern  Array of simple type names, e.g. {"IProgressMonitor", "Class"}
     *                      For array types use "TypeName[]", e.g. "IQueryParameter[]"
     * @param args          The arguments to pass
     */
    private Object findAndInvoke(Class<?> clazz, Object target,
            String methodName, String[] paramPattern, Object[] args) throws Exception {

        Method matched = null;

        // Search 1: all interfaces (most reliable for OSGi)
        List<Class<?>> interfaces = getAllInterfaces(clazz);
        for (Class<?> iface : interfaces) {
            matched = matchMethod(iface, methodName, paramPattern);
            if (matched != null) break;
        }

        // Search 2: the concrete class itself
        if (matched == null) {
            matched = matchMethod(clazz, methodName, paramPattern);
        }

        if (matched == null) {
            // Build diagnostic message
            StringBuilder sb = new StringBuilder();
            sb.append("Method '").append(methodName).append("' matching [");
            sb.append(String.join(", ", paramPattern));
            sb.append("] not found on ").append(clazz.getName()).append("\n");
            sb.append("Available methods:\n");
            for (Method m : clazz.getMethods()) {
                sb.append("  ").append(m.getName()).append("(");
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    sb.append(simpleNameOf(pts[i]));
                    if (i < pts.length - 1) sb.append(", ");
                }
                sb.append(") : ").append(m.getReturnType().getSimpleName()).append("\n");
            }
            throw new Exception(sb.toString());
        }

        // Make accessible (handles package-private implementations of public interfaces)
        matched.setAccessible(true);

        try {
            return matched.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getTargetException();

            // Build detailed diagnostic for the actual error
            StringBuilder diag = new StringBuilder();
            diag.append("'").append(methodName).append("' threw: ");
            diag.append(cause.getClass().getName()).append(" - ").append(cause.getMessage());
            diag.append("\nMethod: ").append(matched.toGenericString());
            diag.append("\nTarget: ").append(clazz.getName());
            Class<?>[] pTypes = matched.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                diag.append("\n  Arg[").append(i).append("]: expected=");
                diag.append(pTypes[i].getName());
                diag.append(" (CL:").append(clInfo(pTypes[i])).append(")");
                if (args[i] == null) {
                    diag.append(", actual=null");
                } else {
                    diag.append(", actual=").append(args[i].getClass().getName());
                    diag.append(" (CL:").append(clInfo(args[i].getClass())).append(")");
                    diag.append(", assignable=").append(
                            pTypes[i].isAssignableFrom(args[i].getClass()));
                }
            }
            throw new Exception(diag.toString(), cause);
        } catch (IllegalArgumentException e) {
            // This means the JVM itself rejected the arguments — ClassLoader mismatch
            StringBuilder diag = new StringBuilder();
            diag.append("!!! JVM IllegalArgumentException calling '").append(methodName).append("' !!!\n");
            diag.append("Method: ").append(matched.toGenericString()).append("\n");
            Class<?>[] pTypes = matched.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                diag.append("  Param[").append(i).append("]: ").append(pTypes[i].getName());
                diag.append(" CL=").append(clInfo(pTypes[i])).append("\n");
                if (args[i] == null) {
                    diag.append("  Arg[").append(i).append("]: null\n");
                } else {
                    diag.append("  Arg[").append(i).append("]: ").append(args[i].getClass().getName());
                    diag.append(" CL=").append(clInfo(args[i].getClass())).append("\n");
                    diag.append("  Assignable: ").append(
                            pTypes[i].isAssignableFrom(args[i].getClass())).append("\n");
                }
            }
            throw new Exception(diag.toString(), e);
        }
    }

    /**
     * Tries to find a method on the given class whose name and parameter simple names
     * match the pattern. Returns null if not found.
     */
    private Method matchMethod(Class<?> clazz, String methodName, String[] paramPattern) {
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != paramPattern.length) continue;

            Class<?>[] params = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < params.length; i++) {
                if (!simpleNameOf(params[i]).equals(paramPattern[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return m;
        }
        return null;
    }

    /**
     * Returns the "simple name" of a class for pattern matching.
     * For arrays: "IQueryParameter[]"
     * For regular classes: "IProgressMonitor", "Class", "String" etc.
     */
    private String simpleNameOf(Class<?> c) {
        if (c.isArray()) {
            return c.getComponentType().getSimpleName() + "[]";
        }
        return c.getSimpleName();
    }

    /**
     * Logs all public methods on a class to the debug log.
     */
    private void logMethods(String label, Class<?> clazz) {
        if (debugLog == null) debugLog = new StringBuilder();
        debugLog.append("\n--- ").append(label).append(" ---\n");
        for (Method m : clazz.getMethods()) {
            debugLog.append("  ").append(m.getName()).append("(");
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                debugLog.append(simpleNameOf(pts[i]));
                if (i < pts.length - 1) debugLog.append(", ");
            }
            debugLog.append(") : ").append(m.getReturnType().getSimpleName()).append("\n");
        }
    }

    /**
     * Writes debug log to ~/sap_export_debug.txt
     */
    private void flushDebugLog() {
        if (debugLog == null || debugLog.length() == 0) return;
        try {
            java.io.File f = new java.io.File(System.getProperty("user.home"), "sap_export_debug.txt");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(debugLog.toString());
            }
        } catch (Exception e) { /* ignore */ }
    }

    /** ClassLoader info for diagnostics */
    private String clInfo(Class<?> c) {
        if (c == null) return "null";
        ClassLoader cl = c.getClassLoader();
        return cl == null ? "bootstrap" : cl.toString();
    }

    /** Collect all interfaces (including inherited) for a class */
    private List<Class<?>> getAllInterfaces(Class<?> cls) {
        List<Class<?>> result = new ArrayList<>();
        while (cls != null) {
            for (Class<?> iface : cls.getInterfaces()) {
                if (!result.contains(iface)) {
                    result.add(iface);
                    addParentInterfaces(iface, result);
                }
            }
            cls = cls.getSuperclass();
        }
        return result;
    }

    private void addParentInterfaces(Class<?> iface, List<Class<?>> result) {
        for (Class<?> parent : iface.getInterfaces()) {
            if (!result.contains(parent)) {
                result.add(parent);
                addParentInterfaces(parent, result);
            }
        }
    }

    // =========================================================
    // Recursive package traversal
    // =========================================================
    private void recurse(String packageName, String relativePath,
            File baseDir, String destId,
            List<String> exported, List<String> skipped,
            StringBuilder log, int depth) throws Exception {

        if (depth > 10) return;

        String path  = "/sap/bc/adt/repository/nodestructure";
        String query = "parent_name=" + packageName
                + "&parent_tech_name=" + packageName
                + "&parent_type=DEVC%2FK"
                + "&withShortDescriptions=true";

        String rawXml = adtCall(destId, "POST", path, query,
                "application/vnd.sap.as+xml");

        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.parse(new org.xml.sax.InputSource(
                new java.io.StringReader(rawXml)));
        NodeList nodes = doc.getElementsByTagName("SEU_ADT_REPOSITORY_OBJ_NODE");

        if (depth == 0 && nodes.getLength() == 0) {
            throw new Exception("No objects found in: " + packageName
                    + "\nXML:\n"
                    + (rawXml.length() > 800 ? rawXml.substring(0, 800) : rawXml));
        }

        List<String[]> subPackages = new ArrayList<>();

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el      = (Element) nodes.item(i);
            String objType  = text(el, "OBJECT_TYPE");
            String objName  = text(el, "OBJECT_NAME");
            String techName = text(el, "TECH_NAME");
            String objUri   = text(el, "OBJECT_URI");

            if (objType == null || objType.isEmpty()) continue;

            String mainType = objType.contains("/")
                    ? objType.substring(0, objType.indexOf("/")) : objType;

            if ("DEVC".equals(mainType)) {
                String sub = (objName != null && !objName.isEmpty()) ? objName : techName;
                if (sub != null && !sub.isEmpty() && !sub.equals(packageName)) {
                    String subPath = relativePath.isEmpty()
                            ? sub.toLowerCase()
                            : relativePath + File.separator + sub.toLowerCase();
                    subPackages.add(new String[]{ sub, subPath });
                }
            } else if (objUri != null && !objUri.isEmpty()) {
                String name = (objName != null && !objName.isEmpty()) ? objName : techName;

                // Skip types that don't have downloadable source code
                if (SKIP_SOURCE_TYPES.contains(mainType)) {
                    skipped.add(name);
                    log.append("SKIP: ").append(name)
                       .append(" (").append(mainType).append(" - no source)\n");
                    continue;
                }

                try {
                    String source = adtCall(destId, "GET",
                            objUri + "/source/main", null, "text/plain");
                    if (source != null && !source.isEmpty()) {
                        saveFile(baseDir, relativePath, mainType, name, source);
                        exported.add(name);
                        log.append("OK  : ").append(relativePath).append("/")
                           .append(mainType).append("/").append(name).append("\n");
                    } else {
                        skipped.add(name);
                        log.append("SKIP: ").append(name).append(" (empty source)\n");
                    }
                } catch (Exception e) {
                    skipped.add(name);
                    // Extract just the short error message, not the full diagnostic
                    String errMsg = e.getMessage();
                    if (errMsg != null) {
                        int nlIdx = errMsg.indexOf('\n');
                        if (nlIdx > 0) errMsg = errMsg.substring(0, nlIdx);
                    }
                    log.append("FAIL: ").append(name)
                       .append(" (").append(mainType).append(") - ").append(errMsg).append("\n");
                }
            }
        }

        for (String[] sub : subPackages) {
            recurse(sub[0], sub[1], baseDir, destId, exported, skipped, log, depth + 1);
        }
    }

    private void saveFile(File baseDir, String relativePath,
            String objType, String objName, String source) throws Exception {
        String folder = FOLDER_MAP.getOrDefault(objType, objType.toLowerCase());
        String ext    = EXT_MAP.getOrDefault(objType, ".abap");
        File dir = new File(baseDir, relativePath.isEmpty()
                ? folder : relativePath + File.separator + folder);
        dir.mkdirs();
        String fileName = objName.toLowerCase().replaceAll("[^a-z0-9_]", "_") + ext;
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(new File(dir, fileName)), StandardCharsets.UTF_8)) {
            w.write(source);
        }
    }

    private String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() > 0) {
            String t = nl.item(0).getTextContent();
            return t != null ? t.trim() : null;
        }
        return null;
    }
}