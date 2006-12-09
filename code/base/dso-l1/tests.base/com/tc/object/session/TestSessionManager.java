/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.session;


public class TestSessionManager implements SessionManager, SessionProvider {

  public boolean isCurrentSession = true;
  public SessionID sessionID = SessionID.NULL_ID;
  
  public SessionID getSessionID() {
    return sessionID;
  }

  public void newSession() {
    return;
  }

  public boolean isCurrentSession(SessionID theSessionID) {
    return isCurrentSession;
  }

}
