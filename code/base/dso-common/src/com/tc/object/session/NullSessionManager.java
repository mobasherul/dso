/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.session;

import com.tc.exception.ImplementMe;

public class NullSessionManager implements SessionManager, SessionProvider {

  public SessionID getSessionID() {
    return SessionID.NULL_ID;
  }

  public void newSession() {
    return;
  }

  public boolean isCurrentSession(SessionID sessionID) {
    return true;
  }
  
  public void setBatchSessionID() {
    throw new ImplementMe();
  }
  
  public void clrBatchSessionID() {
    throw new ImplementMe();
  }

}
