import { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage } from "electron";
import * as path from "path";
import Store from "electron-store";
import RelayClient from "./relay-client";
import MessageRouter from "./message-router";
import projectStore from "./project-store";
import ptyManager from "./pty-manager";
import { t, getLang, setLang, getAllMessages, Lang } from "./i18n";

interface AgentConfig {
  serverUrl: string;
  agentId: string;
  token: string;
}

interface AppSettings {
  autoStart: boolean;
  silentLaunch: boolean;
}

const configStore = new Store<AgentConfig>({
  defaults: {
    serverUrl: "ws://localhost:8080/ws",
    agentId: "",
    token: "",
  },
});

const appSettingsStore = new Store<AppSettings>({
  name: "app-settings",
  defaults: {
    autoStart: false,
    silentLaunch: false,
  },
});

const projectWindows: Map<string, BrowserWindow> = new Map();
let tray: Tray | null = null;
let mainWindow: BrowserWindow | null = null;
let relayClient: RelayClient | null = null;

function loadConfig(): AgentConfig {
  return {
    serverUrl: (process.env.RELAY_SERVER_URL ?? configStore.get("serverUrl")) as string,
    agentId: (process.env.AGENT_ID ?? configStore.get("agentId")) as string,
    token: (process.env.AGENT_TOKEN ?? configStore.get("token")) as string,
  };
}

function createTray(): Tray {
  const iconPath = path.join(__dirname, "..", "..", "assets", "tray-icon.png");
  const icon = nativeImage.createFromPath(iconPath);
  const trayInstance = new Tray(icon.isEmpty() ? nativeImage.createEmpty() : icon);
  trayInstance.setToolTip(t("app.name"));
  rebuildTrayMenu(trayInstance);
  trayInstance.on("click", () => {
    // Click tray icon to show main window
    showMainWindow();
  });
  return trayInstance;
}

function rebuildTrayMenu(trayInstance?: Tray): void {
  const tr = trayInstance ?? tray;
  if (!tr) return;
  const projects = projectStore.getAll();
  const projectItems: Electron.MenuItemConstructorOptions[] = projects.map((p) => ({
    label: p.name,
    click: () => createProjectWindow(p.id, p.name),
  }));

  const menu = Menu.buildFromTemplate([
    { label: t("app.name"), enabled: false },
    { type: "separator" },
    { label: t("tray.showMain"), click: () => showMainWindow() },
    ...(projectItems.length > 0
      ? projectItems
      : [{ label: t("tray.noProjects"), enabled: false } as Electron.MenuItemConstructorOptions]),
    { type: "separator" },
    { label: t("tray.settings"), click: () => openSettingsWindow() },
    { type: "separator" },
    { label: t("tray.quit"), click: () => app.quit() },
  ]);
  tr.setContextMenu(menu);
}

function showMainWindow(): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.show();
    mainWindow.focus();
    return;
  }
  mainWindow = new BrowserWindow({
    width: 600,
    height: 520,
    title: t("app.name"),
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.loadFile(path.join(__dirname, "..", "..", "renderer", "settings.html"));
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

function createProjectWindow(projectId: string, projectName: string): BrowserWindow {
  const existing = projectWindows.get(projectId);
  if (existing && !existing.isDestroyed()) {
    existing.focus();
    return existing;
  }

  const win = new BrowserWindow({
    width: 1000,
    height: 700,
    title: projectName + " - " + t("app.name"),
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),

      contextIsolation: true,
      nodeIntegration: false,
    },
    backgroundColor: "#1e1e1e",
  });

  win.loadFile(path.join(__dirname, "..", "..", "renderer", "index.html"));

  win.webContents.on("did-finish-load", () => {
    win.webContents.send("project-id", projectId);
  });

  win.on("closed", () => {
    projectWindows.delete(projectId);
  });

  projectWindows.set(projectId, win);

  // Ensure PTY session exists and forward output to this window
  if (!ptyManager.isAlive(projectId)) {
    const project = projectStore.getById(projectId);
    if (project) {
      ptyManager.spawn(projectId, project.path);
    }
  }

  const session = ptyManager.get(projectId);
  if (session) {
    session.pty.onData((data: string) => {
      if (!win.isDestroyed()) {
        win.webContents.send("pty-output", data);
      }
    });
  }

  return win;
}

function openSettingsWindow(): void {
  // Reuse main window if it exists
  showMainWindow();
}

function initRelay(config: AgentConfig): void {
  if (!config.agentId || !config.token) {
    console.warn("[Main] agentId or token not configured — relay not started");
    return;
  }

  relayClient = new RelayClient(config.serverUrl, config.agentId, config.token);
  new MessageRouter(relayClient);

  relayClient.on("connected", () => {
    console.log("[Main] Relay connected");
    if (tray) tray.setToolTip(t("tray.connected"));
  });

  relayClient.on("disconnected", () => {
    console.log("[Main] Relay disconnected");
    if (tray) tray.setToolTip(t("tray.disconnected"));
  });

  relayClient.on("error", (err: Error) => {
    console.error("[Main] Relay error:", err.message);
  });

  relayClient.connect();
}

// IPC handlers
ipcMain.handle("get-projects", () => projectStore.getAll());

ipcMain.on("open-project-window", (_event, projectId: string) => {
  const project = projectStore.getById(projectId);
  if (project) createProjectWindow(project.id, project.name);
});

ipcMain.on("pty-write", (event, data: string) => {
  const win = BrowserWindow.fromWebContents(event.sender);
  if (!win) return;
  for (const [projectId, w] of projectWindows.entries()) {
    if (w === win) {
      try {
        ptyManager.write(projectId, data);
      } catch (err) {
        console.error("[Main] pty-write error:", err);
      }
      break;
    }
  }
});

ipcMain.handle("get-config", () => loadConfig());

ipcMain.handle("save-config", (_event, config: Partial<AgentConfig>) => {
  if (config.serverUrl !== undefined) configStore.set("serverUrl", config.serverUrl);
  if (config.agentId !== undefined) configStore.set("agentId", config.agentId);
  if (config.token !== undefined) configStore.set("token", config.token);
  return true;
});

ipcMain.handle("get-e2e-status", () => {
  return {
    enabled: relayClient?.isE2EEnabled() ?? false,
    publicKey: relayClient?.getE2E().getPublicKey() ?? "",
  };
});

ipcMain.handle("set-e2e-enabled", (_event, enabled: boolean) => {
  if (relayClient) relayClient.setE2EEnabled(enabled);
  return true;
});

ipcMain.handle("reconnect-relay", () => {
  if (relayClient) {
    relayClient.disconnect();
  }
  const config = loadConfig();
  initRelay(config);
  return true;
});

ipcMain.handle("get-lang", () => getLang());

ipcMain.handle("set-lang", (_event, lang: Lang) => {
  setLang(lang);
  if (tray) {
    tray.setToolTip(t("app.name"));
    rebuildTrayMenu();
  }
  return true;
});

ipcMain.handle("get-i18n-messages", () => getAllMessages());

ipcMain.handle("get-app-settings", () => {
  return {
    autoStart: appSettingsStore.get("autoStart") as boolean,
    silentLaunch: appSettingsStore.get("silentLaunch") as boolean,
  };
});

ipcMain.handle("set-app-settings", (_event, settings: Partial<AppSettings>) => {
  if (settings.autoStart !== undefined) {
    appSettingsStore.set("autoStart", settings.autoStart);
    app.setLoginItemSettings({ openAtLogin: settings.autoStart });
  }
  if (settings.silentLaunch !== undefined) {
    appSettingsStore.set("silentLaunch", settings.silentLaunch);
  }
  return true;
});

app.whenReady().then(() => {
  tray = createTray();
  const config = loadConfig();
  initRelay(config);

  // Open main window unless silent launch is configured
  const silentLaunch = appSettingsStore.get("silentLaunch") as boolean;
  if (!silentLaunch) {
    showMainWindow();
  }
});

app.on("window-all-closed", (_event: Electron.Event) => {
  // Keep running in system tray — do not quit when all windows close
  _event.preventDefault();
});

app.on("before-quit", () => {
  if (relayClient) relayClient.disconnect();
  for (const [id] of projectWindows) {
    ptyManager.kill(id);
  }
});
