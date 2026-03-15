package auth

import "sync"

// DeviceRegistry maps device IDs to their bound agent IDs.
type DeviceRegistry struct {
	m sync.Map // deviceID -> agentID
}

// Register binds a device to an agent.
func (r *DeviceRegistry) Register(deviceID, agentID string) {
	r.m.Store(deviceID, agentID)
}

// GetAgentID returns the agent ID bound to the given device, if any.
func (r *DeviceRegistry) GetAgentID(deviceID string) (string, bool) {
	v, ok := r.m.Load(deviceID)
	if !ok {
		return "", false
	}
	return v.(string), true
}

// Unregister removes a device from the registry.
func (r *DeviceRegistry) Unregister(deviceID string) {
	r.m.Delete(deviceID)
}
