import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('claudeAgent', {
  onPtyOutput: (callback: (data: string) => void) => {
    ipcRenderer.on('pty-output', (_event, data: string) => callback(data));
  },
  writePty: (data: string) => {
    ipcRenderer.send('pty-write', data);
  },
  getProjects: () => ipcRenderer.invoke('get-projects'),
  openProjectWindow: (projectId: string) => {
    ipcRenderer.send('open-project-window', projectId);
  },
  getConfig: () => ipcRenderer.invoke('get-config'),
  saveConfig: (config: Record<string, string>) => ipcRenderer.invoke('save-config', config),
  onProjectId: (callback: (id: string) => void) => {
    ipcRenderer.on('project-id', (_event, id: string) => callback(id));
  },
  getE2EStatus: () => ipcRenderer.invoke('get-e2e-status'),
  setE2EEnabled: (enabled: boolean) => ipcRenderer.invoke('set-e2e-enabled', enabled),
  reconnectRelay: () => ipcRenderer.invoke('reconnect-relay'),
});
