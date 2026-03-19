package com.urcm.abap.exporter.handlers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.handlers.HandlerUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ExportHandler extends AbstractHandler {

    private static final Map<String, String> FOLDER_MAP = new HashMap<>();
    private static final Map<String, String> EXT_MAP = new HashMap<>();

    static {
        FOLDER_MAP.put("CLAS", "clas");
        FOLDER_MAP.put("INTF", "intf");
        FOLDER_MAP.put("DDLS", "ddls");
        FOLDER_MAP.put("DDLX", "ddlx");
        FOLDER_MAP.put("BDEF", "bdef");
        FOLDER_MAP.put("SRVD", "srvd");
        FOLDER_MAP.put("SRVB", "srvb");
        FOLDER_MAP.put("DCLS", "dcls");
        FOLDER_MAP.put("PROG", "prog");
        FOLDER_MAP.put("FUGR", "fugr");
        FOLDER_MAP.put("TABL", "tabl");
        FOLDER_MAP.put("TTYP", "ttyp");
        FOLDER_MAP.put("DTEL", "dtel");
        FOLDER_MAP.put("DOMA", "doma");
        FOLDER_MAP.put("MSAG", "msag");
        FOLDER_MAP.put("DEVC", "devc");
        FOLDER_MAP.put("ENHO", "enho");
        FOLDER_MAP.put("ENHS", "enhs");
        FOLDER_MAP.put("SMIM", "smim");
        FOLDER_MAP.put("XSLT", "xslt");

        EXT_MAP.put("CLAS", ".clas.abap");
        EXT_MAP.put("INTF", ".intf.abap");
        EXT_MAP.put("DDLS", ".asddls");
        EXT_MAP.put("DDLX", ".asddlxs");
        EXT_MAP.put("BDEF", ".asbdef");
        EXT_MAP.put("SRVD", ".asrvd");
        EXT_MAP.put("SRVB", ".asrvb");
        EXT_MAP.put("DCLS", ".asdcls");
        EXT_MAP.put("PROG", ".prog.abap");
        EXT_MAP.put("FUGR", ".fugr.abap");
        EXT_MAP.put("TABL", ".tabl.asx");
        EXT_MAP.put("TTYP", ".ttyp.asx");
        EXT_MAP.put("DTEL", ".dtel.asx");
        EXT_MAP.put("DOMA", ".doma.asx");
        EXT_MAP.put("MSAG", ".msag.asx");
    }

    // Stored CSRF token and cookies (shared across requests)
    private String csrfToken;
    private String sessionCookies;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);

        String packageName = "";
        Object selectedElement = null;

        if (selection instanceof IStructuredSelection) {
            IStructuredSelection ss = (IStructuredSelection) selection;
            selectedElement = ss.getFirstElement();
            if (selectedElement != null) {
                packageName = selectedElement.toString();
            }
        }

        // Auto-detect connection info
        String[] autoInfo = detectConnectionInfo(selectedElement);
        String baseUrl = null;
        String sapUser = null;
        String sapClient = null;

        if (autoInfo != null && autoInfo[0] != null) {
            baseUrl = autoInfo[0];
            sapUser = autoInfo[1];
            sapClient = autoInfo[2];
        }

        // Ask for package name
        InputDialog pkgDialog = new InputDialog(shell, "Export ABAP Package",
                "Package to export:", packageName, null);
        if (pkgDialog.open() != Window.OK) return null;
        packageName = pkgDialog.getValue().toUpperCase().trim();

        if (baseUrl != null) {
            boolean confirm = MessageDialog.openConfirm(shell, "Connection Detected",
                    "URL: " + baseUrl + "\nUser: " + sapUser + "\nClient: " + sapClient
                    + "\n\nUse this connection?");
            if (!confirm) baseUrl = null;
        }

        if (baseUrl == null) {
            InputDialog hostDialog = new InputDialog(shell, "SAP Application Server",
                    "Enter server hostname or IP:", "", null);
            if (hostDialog.open() != Window.OK) return null;
            String host = hostDialog.getValue().trim();
            if (host.isEmpty()) {
                MessageDialog.openError(shell, "Error", "Hostname cannot be empty.");
                return null;
            }

            InputDialog instDialog = new InputDialog(shell, "Instance Number",
                    "Enter Instance Number (e.g. 00, 01):", "", null);
            if (instDialog.open() != Window.OK) return null;
            String instNr = instDialog.getValue().trim();
            if (instNr.isEmpty()) {
                MessageDialog.openError(shell, "Error", "Instance number cannot be empty.");
                return null;
            }
            baseUrl = "https://" + host + ":443" + instNr;

            InputDialog clientDialog = new InputDialog(shell, "SAP Client",
                    "Enter SAP Client (e.g. 100):", "", null);
            if (clientDialog.open() != Window.OK) return null;
            sapClient = clientDialog.getValue().trim();

            InputDialog userDialog = new InputDialog(shell, "SAP User", "Enter SAP username:", "", null);
            if (userDialog.open() != Window.OK) return null;
            sapUser = userDialog.getValue().trim();
        }

        // Password dialog with masked input
        PasswordDialog passDialog = new PasswordDialog(shell, baseUrl, sapUser);
        if (passDialog.open() != Window.OK) return null;
        String pass = passDialog.getPassword();

        // Ask for export directory
        DirectoryDialog dirDialog = new DirectoryDialog(shell);
        dirDialog.setText("Export: " + packageName);
        String targetDir = dirDialog.open();
        if (targetDir == null) return null;

        // Run export
        final String fPkg = packageName;
        final String fUrl = baseUrl;
        final String fUser = sapUser;
        final String fPass = pass;
        final String fClient = sapClient;

        try {
            final StringBuilder resultLog = new StringBuilder();

            BusyIndicator.showWhile(shell.getDisplay(), () -> {
                try {
                    setupTrustAllSSL();
                    fetchCsrfToken(fUrl, fUser, fPass, fClient);

                    // Recursively list all objects with package path info
                    // Each entry: [type, name, uri, packagePath]
                    List<String[]> objects = new ArrayList<>();
                    listPackageObjectsRecursive(fUrl, fPkg, fUser, fPass, fClient, objects, 0, fPkg);

                    int exported = 0;
                    int skipped = 0;

                    for (String[] obj : objects) {
                        String objType = obj[0];
                        String objName = obj[1];
                        String objUri = obj[2];
                        String pkgPath = obj[3]; // package hierarchy path

                        try {
                            String source = fetchSource(fUrl, objUri, fUser, fPass, fClient);
                            if (source != null && !source.isEmpty()) {
                                saveToFile(targetDir, pkgPath, objType, objName, source);
                                exported++;
                                resultLog.append("OK: ").append(pkgPath).append("/")
                                        .append(objType).append("/").append(objName).append("\n");
                            } else {
                                skipped++;
                                resultLog.append("SKIP: ").append(pkgPath).append("/")
                                        .append(objType).append("/").append(objName).append("\n");
                            }
                        } catch (Exception e) {
                            skipped++;
                            resultLog.append("FAIL: ").append(objType).append("/").append(objName)
                                    .append(" - ").append(e.getMessage()).append("\n");
                        }
                    }

                    resultLog.insert(0, "Package: " + fPkg + "\nExported: " + exported
                            + "\nSkipped: " + skipped + "\nTotal: " + objects.size() + "\n\n");

                } catch (Exception e) {
                    resultLog.append("ERROR: ").append(e.getMessage());
                }
            });

            String logStr = resultLog.toString();
            if (logStr.length() > 3000) logStr = logStr.substring(0, 3000) + "...";
            MessageDialog.openInformation(shell, "Export Complete", logStr);

        } catch (Exception e) {
            MessageDialog.openError(shell, "Export Failed", e.getMessage());
        }

        return null;
    }

    // ======================== Password Dialog (masked) ========================

    private static class PasswordDialog extends Dialog {
        private Text passwordText;
        private String password = "";
        private final String systemUrl;
        private final String userName;

        protected PasswordDialog(Shell parentShell, String systemUrl, String userName) {
            super(parentShell);
            this.systemUrl = systemUrl;
            this.userName = userName;
        }

        @Override
        protected void configureShell(Shell newShell) {
            super.configureShell(newShell);
            newShell.setText("SAP Password");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            Composite container = new Composite(area, SWT.NONE);
            container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            container.setLayout(new GridLayout(1, false));

            Label infoLabel = new Label(container, SWT.WRAP);
            infoLabel.setText("System: " + systemUrl + "\nUser: " + userName);
            infoLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Label separator = new Label(container, SWT.NONE);
            separator.setText("");

            Label passLabel = new Label(container, SWT.NONE);
            passLabel.setText("Password:");

            passwordText = new Text(container, SWT.BORDER | SWT.PASSWORD);
            passwordText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            passwordText.setFocus();

            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
            createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        }

        @Override
        protected void okPressed() {
            password = passwordText.getText();
            super.okPressed();
        }

        public String getPassword() {
            return password;
        }
    }

    // ======================== CSRF Token ========================

    private void fetchCsrfToken(String baseUrl, String user, String pass, String client) throws Exception {
        String clientParam = (client != null && !client.isEmpty()) ? "?sap-client=" + client : "";
        String url = baseUrl + "/sap/bc/adt/repository/nodestructure" + clientParam;

        HttpURLConnection conn = createConnection(url, user, pass);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("x-csrf-token", "fetch");

        try {
            conn.getResponseCode();
        } catch (Exception ignored) {}

        csrfToken = conn.getHeaderField("x-csrf-token");
        StringBuilder cookieSb = new StringBuilder();
        for (String key : conn.getHeaderFields().keySet()) {
            if ("Set-Cookie".equalsIgnoreCase(key)) {
                for (String cookie : conn.getHeaderFields().get(key)) {
                    if (cookieSb.length() > 0) cookieSb.append("; ");
                    cookieSb.append(cookie.split(";")[0]);
                }
            }
        }
        sessionCookies = cookieSb.toString();
        conn.disconnect();
    }

    // ======================== Recursive Package Listing ========================

    /**
     * Recursively list package objects, descending into sub-packages.
     * Each result entry: [type, name, uri, packagePath]
     */
    private void listPackageObjectsRecursive(String baseUrl, String pkg, String user, String pass,
            String client, List<String[]> result, int depth, String pkgPath) throws Exception {
        if (depth > 10) return;

        String clientParam = (client != null && !client.isEmpty()) ? "&sap-client=" + client : "";
        String url = baseUrl + "/sap/bc/adt/repository/nodestructure"
                + "?parent_name=" + pkg
                + "&parent_tech_name=" + pkg
                + "&parent_type=DEVC%2FK"
                + "&withShortDescriptions=true"
                + clientParam;

        HttpURLConnection conn = createConnection(url, user, pass);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/vnd.sap.as+xml");
        conn.setRequestProperty("Content-Type", "application/xml");
        if (csrfToken != null) conn.setRequestProperty("x-csrf-token", csrfToken);
        if (sessionCookies != null) conn.setRequestProperty("Cookie", sessionCookies);
        conn.setDoOutput(true);
        conn.getOutputStream().close();

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return;
        }

        String rawXml;
        try (InputStream is = conn.getInputStream()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                rawXml = sb.toString();
            }
        } finally {
            conn.disconnect();
        }

        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.parse(new org.xml.sax.InputSource(new java.io.StringReader(rawXml)));

        NodeList nodes = doc.getElementsByTagName("SEU_ADT_REPOSITORY_OBJ_NODE");

        List<String[]> subPackages = new ArrayList<>(); // [name, path]

        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String objType = getChildText(el, "OBJECT_TYPE");
            String objName = getChildText(el, "OBJECT_NAME");
            String techName = getChildText(el, "TECH_NAME");
            String objUri = getChildText(el, "OBJECT_URI");

            if (objType == null || objType.isEmpty()) continue;

            String mainType = objType.contains("/") ? objType.substring(0, objType.indexOf("/")) : objType;

            if ("DEVC".equals(mainType)) {
                String subPkgName = (objName != null && !objName.isEmpty()) ? objName : techName;
                if (subPkgName != null && !subPkgName.isEmpty() && !subPkgName.equals(pkg)) {
                    // Build hierarchical path: PARENT_PKG/SUB_PKG
                    String subPath = pkgPath + File.separator + subPkgName.toLowerCase();
                    subPackages.add(new String[]{subPkgName, subPath});
                }
            } else {
                if (objUri != null && !objUri.isEmpty()) {
                    result.add(new String[]{mainType, objName != null ? objName : techName, objUri, pkgPath});
                }
            }
        }

        for (String[] subPkg : subPackages) {
            listPackageObjectsRecursive(baseUrl, subPkg[0], user, pass, client, result, depth + 1, subPkg[1]);
        }
    }

    // ======================== Helpers ========================

    private String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() > 0) {
            String text = children.item(0).getTextContent();
            return (text != null) ? text.trim() : null;
        }
        return null;
    }

    private String[] detectConnectionInfo(Object element) {
        if (element == null) return null;
        try {
            Object project = callMethod(element, "getProject");
            if (project == null) {
                Object parent = element;
                for (int i = 0; i < 10 && parent != null; i++) {
                    project = callMethod(parent, "getProject");
                    if (project != null) break;
                    try {
                        Class<?> cls = element.getClass().getClassLoader()
                                .loadClass("org.eclipse.core.resources.IProject");
                        project = parent.getClass().getMethod("getAdapter", Class.class)
                                .invoke(parent, cls);
                        if (project != null) break;
                    } catch (Exception ignored) {}
                    parent = callMethod(parent, "getParent");
                }
            }
            if (project == null) return null;

            Object abapProject = null;
            for (String cn : new String[]{
                    "com.sap.adt.tools.core.project.IAbapProject",
                    "com.sap.adt.project.IAbapProject",
                    "com.sap.adt.project.ui.IAbapProject"}) {
                try {
                    Class<?> clz = element.getClass().getClassLoader().loadClass(cn);
                    abapProject = project.getClass().getMethod("getAdapter", Class.class)
                            .invoke(project, clz);
                    if (abapProject != null) break;
                } catch (Exception ignored) {}
            }
            if (abapProject == null) return null;

            Object destData = callMethod(abapProject, "getDestinationData");
            if (destData == null) return null;

            String user = str(callMethod(destData, "getUser"));
            String client = str(callMethod(destData, "getClient"));

            Object sysConfig = callMethod(destData, "getSystemConfiguration");
            String host = null;
            String sysNr = null;
            if (sysConfig != null) {
                host = str(callMethod(sysConfig, "getHost"));
                if (host == null) host = str(callMethod(sysConfig, "getServer"));
                sysNr = str(callMethod(sysConfig, "getSystemNumber"));
            }

            if (host != null && sysNr != null) {
                String baseUrl = "https://" + host + ":443" + sysNr;
                return new String[]{baseUrl, user, client};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Object callMethod(Object obj, String name) {
        if (obj == null) return null;
        try { return obj.getClass().getMethod(name).invoke(obj); }
        catch (Exception e) { return null; }
    }

    private String str(Object o) { return o != null ? o.toString() : null; }

    @SuppressWarnings("all")
    private void setupTrustAllSSL() throws Exception {
        TrustManager[] tm = {new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tm, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
    }

    private String fetchSource(String baseUrl, String objectUri, String user, String pass, String client)
            throws Exception {
        String clientParam = (client != null && !client.isEmpty()) ? "?sap-client=" + client : "";
        String url = baseUrl + objectUri;
        if (!url.contains("/source/main")) url += "/source/main";
        url += clientParam;

        HttpURLConnection conn = createConnection(url, user, pass);
        conn.setRequestProperty("Accept", "text/plain");
        if (sessionCookies != null) conn.setRequestProperty("Cookie", sessionCookies);

        int code = conn.getResponseCode();
        if (code == 404 || code == 400 || code == 405) {
            conn.disconnect();
            return null;
        }

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    /**
     * Save file preserving package hierarchy:
     * targetDir / pkgPath / src / type_folder / filename.ext
     */
    private void saveToFile(String targetDir, String pkgPath, String objType, String objName, String content)
            throws Exception {
        String folder = FOLDER_MAP.getOrDefault(objType, objType.toLowerCase());
        String ext = EXT_MAP.getOrDefault(objType, ".abap");

        // Build path: exportDir/package_hierarchy/src/type/file
        File dir = new File(targetDir + File.separator + pkgPath.toLowerCase()
                + File.separator + "src" + File.separator + folder);
        dir.mkdirs();

        String fileName = objName.toLowerCase().replaceAll("[^a-z0-9_]", "_") + ext;
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(new File(dir, fileName)), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    private HttpURLConnection createConnection(String url, String user, String pass) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        String auth = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + auth);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        return conn;
    }
}
