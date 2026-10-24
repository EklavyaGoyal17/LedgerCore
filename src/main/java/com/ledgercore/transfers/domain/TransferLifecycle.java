package com.ledgercore.transfers.domain;

public final class TransferLifecycle {
  public enum State {
    CREATED,
    PROCESSING,
    COMPLETED,
    FAILED
  }

  private State state = State.CREATED;

  public State state() {
    return state;
  }

  public void transition(State next) {
    if (!((state == State.CREATED && next == State.PROCESSING)
        || (state == State.PROCESSING && (next == State.COMPLETED || next == State.FAILED)))) {
      throw new IllegalStateException("Illegal transfer transition: " + state + " -> " + next);
    }
    state = next;
  }
}
