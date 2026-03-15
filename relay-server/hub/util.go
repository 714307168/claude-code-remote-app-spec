package hub

import "github.com/google/uuid"

// newID returns a new random UUID string.
func newID() string {
	return uuid.New().String()
}
