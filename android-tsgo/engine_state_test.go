package tsgo

import (
	"errors"
	"testing"
	"time"
)

func TestEngineControllerLifecycleAndAdmission(t *testing.T) {
	engine := newEngineController()
	if got := engine.snapshot().State; got != engineIdle {
		t.Fatalf("initial state = %s", got)
	}

	engine.beginLifecycle()
	engine.finishLifecycle("model-a", 1024, nil)
	ready := engine.snapshot()
	if ready.State != engineReady || ready.Model != "model-a" || ready.Dimensions != 1024 {
		t.Fatalf("ready snapshot = %+v", ready)
	}

	busy, ok := engine.tryBeginInference()
	if !ok || busy.State != engineBusy || busy.BusySince.IsZero() {
		t.Fatalf("admission = %+v, %t", busy, ok)
	}
	if second, ok := engine.tryBeginInference(); ok || second.State != engineBusy {
		t.Fatalf("second admission = %+v, %t", second, ok)
	}
	engine.finishInference(nil)
	if got := engine.snapshot().State; got != engineReady {
		t.Fatalf("state after inference = %s", got)
	}
}

func TestEngineControllerLifecycleWinsOverFinishingInference(t *testing.T) {
	engine := newEngineController()
	engine.beginLifecycle()
	engine.finishLifecycle("model-a", 3, nil)
	if _, ok := engine.tryBeginInference(); !ok {
		t.Fatal("failed to begin inference")
	}

	lifecycleStarted := make(chan struct{})
	lifecycleDone := make(chan struct{})
	go func() {
		close(lifecycleStarted)
		engine.beginLifecycle()
		engine.finishLifecycle("model-b", 4, nil)
		close(lifecycleDone)
	}()
	<-lifecycleStarted

	deadline := time.Now().Add(time.Second)
	for engine.snapshot().State != engineLoading && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	engine.finishInference(nil)
	<-lifecycleDone
	got := engine.snapshot()
	if got.State != engineReady || got.Model != "model-b" || got.Dimensions != 4 {
		t.Fatalf("final snapshot = %+v", got)
	}
}

func TestEngineControllerFailedLifecycle(t *testing.T) {
	engine := newEngineController()
	engine.beginLifecycle()
	engine.finishLifecycle("", 0, errors.New("bad model"))
	got := engine.snapshot()
	if got.State != engineFailed || got.LastError != "bad model" {
		t.Fatalf("failed snapshot = %+v", got)
	}
}
