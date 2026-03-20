# ABAP Package Exporter for Eclipse (ADT)

An Eclipse plug-in designed to export ABAP packages and their source code from SAP systems directly to your local file system. It specifically targets developers who need local copies of ABAP objects while maintaining the package hierarchy.

## Key Features

- **Multi-System Support**: Works seamlessly on both **SAP BTP ABAP Environment (Cloud)** and **On-Premise** SAP systems.
- **Recursive Export**: Automatically crawls through sub-packages and exports all contained objects.
- **Hierarchy Preservation**: Recreates the SAP package structure in your local folder system (e.g., `Parent_Package/Sub_Package/src/clas/ZCL_MY_CLASS.clas.abap`).
- **Zero Configuration**: Dynamically detects connection details from your active ADT (ABAP Development Tools) project. No manual host or URL configuration required.
- **Deep Integration**: Uses the internal SAP ADT communication layer to leverage existing authenticated sessions.

## Technical Details

To ensure compatibility across different SAP ADT versions and bypass OSGi visibility constraints, this plugin:
- Uses the **SAP ADT SDK's `IRestResource` API** via a specialized reflection layer.
- Implements a **ClassLoader-safe reflection strategy** (`findAndInvoke`) that matches methods by parameter simple-names, avoiding common "argument type mismatch" errors in OSGi environments.
- Leverages `IAbapProject.getDestinationId()` to automatically handle authentication and connection routing for both Cloud (OAuth) and On-Premise (SAP Logon) destinations.

## Installation

### For Regular Users (Binary)
1. Download the latest `com.urcm.abap.exporter.xxx.jar` from the [GitHub Releases](https://github.com/urcm/eclipse-sap-package-exporter/releases) section.
2. Locate your Eclipse installation directory.
3. Copy the JAR file into the `dropins/` folder.
4. Restart Eclipse.

### For Developers (Source)
If you wish to contribute or build from source:
1. Clone this repository.
2. Open Eclipse with **Plug-in Development Environment (PDE)** installed.
3. Install the **SAP ABAP Development Tools (ADT) SDK**.
4. Import this project as an "Existing Project into Workspace".
5. To test, right-click the project and select **Run As > Eclipse Application**.

## Usage

1. Open the **ABAP Project Explorer** in Eclipse.
2. **Log in** to your ABAP Project (ensure the connection is active).
3. Right-click on any **ABAP Package** you want to export.
4. Select **Export ABAP Package** from the context menu.
5. Enter the **Package Name** and select a **Target Directory** on your local machine.
6. Wait for the process to complete. A summary log will appear with the results.

## Object Handling

### Supported Objects (Source Export)
Includes all objects providing a `/source/main` endpoint:
- Classes (`CLAS`), Interfaces (`INTF`)
- CDS Views (`DDLS`), MetaData Extensions (`DDLX`)
- Behavior Definitions (`BDEF`)
- Programs (`PROG`), Function Groups (`FUGR`)
- Tables (`TABL`), Data Elements (`DTEL`), Domains (`DOMA`)

### Skipped Objects (Non-Source)
Objects that do not contain downloadable ABAP source code are skipped:
- Service Bindings (`SRVB`), Service Definitions (`SRVD`), Service Consumption Models (`SRVC`)
- Message Classes (`MSAG`), MIME Objects (`SMIM`)
- OData/ICF Service Groupings (`IWSG`, `IWSV`, `IWOM`, `IWVB`, etc.)
- Metadata/Config objects (`SUSH`, `SIA6`, `OA2S`, `G4BA`, `EVTB`)

## Folder Structure of Export

The plugin follows a modern source structure, organizing objects by type into subdirectories:

```text
Target_Dir/
└── package_name/
    └── sub_package/
        └── src/
            ├── clas/ (Classes)
            ├── intf/ (Interfaces)
            ├── ddls/ (CDS Views)
            ├── bdef/ (Behavior Definitions)
            └── ...
```

## Security and Privacy

- **No Data Collection**: This plugin does **not** store, transmit, or upload any SAP credentials (user, password), host information, or exported source code to any external server.
- **Local Execution**: All processing and file writing happen entirely on your local machine.
- **Session-Based**: It leverages your existing, authenticated Eclipse ADT session. If your Eclipse session is secure, the plugin's access is secure.

## Troubleshooting and Logs

If you encounter issues or need to verify the ADT API calls:
- A diagnostic log is automatically generated at: `C:\Users\<YourUser>\sap_export_debug.txt`
- This log containing ADT method signatures and URI paths is overwritten on every new export session.

## Legal Disclaimer

- **Trademarks**: SAP, ABAP, and SAP Development Tools (ADT) are trademarks or registered trademarks of **SAP SE** in Germany and in several other countries.
- **Trademarks**: Eclipse is a trademark of the **Eclipse Foundation**.
- **Affiliation**: This project is an independent community-led work and is **not** affiliated with, sponsored by, or endorsed by SAP SE, the Eclipse Foundation, or any other third-party corporation.
- **Warranty**: Use this tool at your own risk. The authors are not responsible for any data loss, system instability, or policy violations within your SAP landscape. Always ensure you have the necessary permissions to export source code.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request or open an Issue for bug reports and feature requests.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.


