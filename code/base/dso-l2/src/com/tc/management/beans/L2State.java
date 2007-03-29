/*
 * All content copyright (c) 2003-2007 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.management.beans;

import com.tc.l2.context.StateChangedEvent;
import com.tc.l2.state.StateChangeListener;
import com.tc.l2.state.StateManager;
import com.tc.util.State;

public class L2State implements StateChangeListener {

  private State serverState = StateManager.PASSIVE_STANDBY;

  public synchronized void setState(State state) {
    if (!validateState(state)) { throw new AssertionError("Unrecognized server state: [" + state.getName() + "]"); }
    serverState = state;
  }

  public synchronized State getState() {
    return serverState;
  }

  private boolean validateState(State state) {
    return (state.equals(StateManager.START_STATE) || state.equals(StateManager.PASSIVE_UNINTIALIZED)
            || state.equals(StateManager.PASSIVE_STANDBY) || state.equals(StateManager.ACTIVE_COORDINATOR));
  }

  public void l2StateChanged(StateChangedEvent sce) {
    setState(sce.getCurrentState());
  }

  public boolean isActiveCoordinator() {
    if (getState().equals(StateManager.ACTIVE_COORDINATOR)) { return true; }
    return false;
  }

  public boolean isPassiveUninitialized() {
    if (getState().equals(StateManager.PASSIVE_UNINTIALIZED)) { return true; }
    return false;
  }

  public boolean isPassiveStandby() {
    if (getState().equals(StateManager.PASSIVE_STANDBY)) { return true; }
    return false;
  }

  public boolean isStartState() {
    if (getState().equals(StateManager.START_STATE)) { return true; }
    return false;
  }

}
