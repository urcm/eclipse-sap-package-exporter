# ABAP Package Exporter for Eclipse (ADT)

An Eclipse plug-in designed to export ABAP packages and their source code from SAP systems directly to your local file system. It specifically targets developers who need local copies of ABAP objects while maintaining the package hierarchy.

## 🚀 Key Features

- **Recursive Export**: Automatically crawls through sub-packages and exports all contained objects.
- **Hierarchy Preservation**: Recretes the SAP package structure in your local folder system (e.g., `Parent_Package/Sub_Package/src/clas/ZCL_MY_CLASS.abap`).
- **Auto-Detection**: Dynamically detects connection details (Host, System Number, User, Client) from your active ADT (ABAP Development Tools) project.
- **Secure Input**: Features a masked password dialog to ensure your SAP credentials remain private.
- **Supported Objects**: Includes Classes (`CLAS`), Interfaces (`INTF`), CDS Views (`DDLS`), Enhancement Options, Programs, and more.

## 📦 Installation

### For Regular Users (Binary)
1. Download the latest `com.urcm.abap.exporter.xxx.jar` from the [Releases](#) section.
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

## 🛠 Usage

1. Open the **ABAP Project Explorer** in Eclipse.
2. Right-click on any **ABAP Package** you want to export.
3. Select **Export ABAP Package** from the context menu.
4. Verify the connection details (auto-detected from your project).
5. Enter your SAP **Password** when prompted.
6. Select a **Target Directory** on your local machine.
7. Wait for the process to complete. A log will appear showing the results.

## 📋 Folder Structure of Export
The plugin follows a modern source structure:
```text
Target_Dir/
└── package_name/
    └── sub_package/
        └── src/
            ├── clas/ (Classes)
            ├── intf/ (Interfaces)
            ├── ddls/ (CDS Views)
            └── ...
```

## 🤝 Contributing
Contributions are welcome! Please feel free to submit a Pull Request or open an Issue for bug reports and feature requests.

## ⚖️ License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
