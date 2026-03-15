package hub

import (
	"sync"

	"github.com/claudecode/relay-server/model"
)

// Queue is a fixed-size ring buffer of Envelopes, safe for concurrent use.
type Queue struct {
	mu   sync.Mutex
	buf  []*model.Envelope
	size int
	head int // next write position
	len  int // number of valid entries
}

// NewQueue allocates a Queue with the given capacity.
func NewQueue(size int) *Queue {
	return &Queue{
		buf:  make([]*model.Envelope, size),
		size: size,
	}
}

// Push appends an envelope, overwriting the oldest entry when full.
func (q *Queue) Push(env *model.Envelope) {
	q.mu.Lock()
	defer q.mu.Unlock()

	q.buf[q.head] = env
	q.head = (q.head + 1) % q.size
	if q.len < q.size {
		q.len++
	}
}

// DrainFrom returns all envelopes whose Seq is greater than lastSeq,
// in insertion order.
func (q *Queue) DrainFrom(lastSeq int64) []*model.Envelope {
	q.mu.Lock()
	defer q.mu.Unlock()

	// oldest entry index
	start := (q.head - q.len + q.size) % q.size

	var out []*model.Envelope
	for i := 0; i < q.len; i++ {
		idx := (start + i) % q.size
		if q.buf[idx] != nil && q.buf[idx].Seq > lastSeq {
			out = append(out, q.buf[idx])
		}
	}
	return out
}
