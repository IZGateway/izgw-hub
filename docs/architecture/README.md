# private — Maintenance Guide

This folder contains architecture diagrams and companion documents for the IZ Gateway project.
The diagrams are kept here (not in a GitHub repo) because they document the production
environment at a level of detail that should not be public.

---

## Files in This Folder

| File | Purpose |
|------|---------|
| `topology.puml` | PlantUML source for the system topology diagram |
| `interactions.puml` | PlantUML source for the key message flows (sequence) diagram |
| `IZ_Gateway_System_Topology.svg` | Generated SVG — rendered from `topology.puml` |
| `IZ_Gateway_Interactions.svg` | Generated SVG — rendered from `interactions.puml` |
| `topology.md` | Senior manager companion doc for the topology diagram |
| `interactions.md` | Senior manager companion doc for the message flows diagram |

---

## Workflow: Making a Change

### Step 1 — Edit the `.puml` source file

Open `topology.puml` or `interactions.puml` in any text editor (Eclipse, VS Code, Notepad++).
PlantUML syntax reference: https://plantuml.com/

### Step 2 — Regenerate the SVG

Run the following commands from this directory (adjust paths if your JDK or Eclipse version changes):

```cmd
set JAVA=C:\jdk-24.0.2\bin\java.exe
set PUML=C:\Users\boonek\eclipse\jee-2025-12\eclipse\configuration\org.eclipse.osgi\1544\0\.cp\lib\plantuml-epl-1.2026.3.jar
set OUTDIR=C:\Users\boonek\eclipse-workspace\izgw-hub\docs\architecture

%JAVA% -jar "%PUML%" -tsvg -o "%OUTDIR%" topology.puml
%JAVA% -jar "%PUML%" -tsvg -o "%OUTDIR%" interactions.puml
```

> **Note:** The SVG filename is determined by the `@startuml <name>` line inside the `.puml` file,
> not the `.puml` filename itself. Current names:
> - `topology.puml` → `IZ_Gateway_System_Topology.svg`
> - `interactions.puml` → `IZ_Gateway_Interactions.svg`

### Step 3 — Upload the new SVG to Confluence

The diagrams live in the **Architecture Team** section of the IGDD Confluence space:

| Page | URL | Attachment to replace |
|------|-----|-----------------------|
| IZ Gateway — System Topology | https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/804814849 | `IZ_Gateway_System_Topology.svg` |
| IZ Gateway — Key Message Flows | https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/804814875 | `IZ_Gateway_Interactions.svg` |

Confluence will automatically version the attachment — the old SVG is preserved in history.

**Via Copilot CLI** (easiest):
```
Upload the updated topology SVG to the IZ Gateway System Topology Confluence page (804814849)
```
Copilot will handle the upload using the MCP tools.

**Manually:**
1. Open the Confluence page
2. Go to **⋯ → Attachments**
3. Drag-and-drop the new SVG onto the existing attachment to create a new version

### Step 4 — Update the `.puml` attachment (if the source changed)

The `.puml` files are also attached to their respective Confluence pages so others can
download and view/edit the source. Replace them the same way as the SVGs if you changed them.

---

## Confluence Pages

| Page | ID | URL |
|------|----|-----|
| Architecture Team (parent) | 22184303 | https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/22184303 |
| IZ Gateway — System Topology | 804814849 | https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/804814849 |
| IZ Gateway — Key Message Flows | 804814875 | https://izgateway.atlassian.net/wiki/spaces/IGDD/pages/804814875 |

---

## Updating the Companion Markdown (`.md`) Files

`topology.md` and `interactions.md` are the plain-language narrative documents.
Edit them directly in any text editor. If you change section headings or component
numbers, keep the `.md` file in sync with the `.puml` source.

After editing an `.md` file, update the corresponding Confluence page by asking Copilot:
```
Update the IZ Gateway System Topology Confluence page with the current content of topology.md
```

---

## PlantUML Tools

- **Eclipse plugin**: Install from Eclipse Marketplace → "PlantUML" to get live preview in the IDE
- **VS Code extension**: "PlantUML" by jebbs — live preview with Alt+D
- **Online renderer**: https://www.plantuml.com/plantuml/uml/ (do not paste sensitive content)
- **Jar location** (as of May 2026):
  `C:\Users\boonek\eclipse\jee-2025-12\eclipse\configuration\org.eclipse.osgi\1544\0\.cp\lib\plantuml-epl-1.2026.3.jar`
