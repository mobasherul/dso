/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.admin;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.io.IOUtils;
import org.dijon.Button;
import org.dijon.Container;
import org.dijon.ContainerResource;
import org.dijon.Item;
import org.dijon.Label;
import org.dijon.List;
import org.dijon.ScrollPane;
import org.dijon.TabbedPane;
import org.dijon.TextArea;
import org.dijon.ToggleButton;

import com.tc.admin.common.StatusView;
import com.tc.admin.common.XContainer;
import com.tc.admin.common.XTree;
import com.tc.admin.common.XTreeModel;
import com.tc.admin.common.XTreeNode;
import com.tc.statistics.beans.StatisticsLocalGathererMBean;
import com.tc.statistics.beans.StatisticsMBeanNames;
import com.tc.statistics.gatherer.exceptions.TCStatisticsGathererAlreadyConnectedException;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;

import javax.management.Notification;
import javax.management.NotificationListener;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;

public class ClusterPanel extends XContainer {
  private AdminClientContext           m_acc;
  private ClusterNode                  m_clusterNode;
  private JTextField                   m_hostField;
  private JTextField                   m_portField;
  private JButton                      m_connectButton;
  static private ImageIcon             m_connectIcon;
  static private ImageIcon             m_disconnectIcon;
  private StatusView                   m_statusView;
  private JButton                      m_shutdownButton;
  private ProductInfoPanel             m_productInfoPanel;
  private TabbedPane                   m_tabbedPane;

  private Button                       m_threadDumpButton;
  private XTree                        m_threadDumpTree;
  private XTreeModel                   m_threadDumpTreeModel;
  private TextArea                     m_threadDumpTextArea;
  private ScrollPane                   m_threadDumpTextScroller;
  private ThreadDumpTreeNode           m_lastSelectedThreadDumpTreeNode;

  private StatisticsGathererListener   m_statsGathererListener;
  private ToggleButton                 m_startGatheringStatsButton;
  private ToggleButton                 m_stopGatheringStatsButton;
  private List                         m_statsSessionsList;
  private DefaultListModel             m_statsSessionsListModel;
  private Container                    m_statsConfigPanel;
  private HashMap                      m_statsControls;
  private StatisticsLocalGathererMBean m_statisticsGathererMBean;
  private String                       m_currentStatsSessionId;
  private Button                       m_exportStatsButton;
  private JProgressBar                 m_exportProgressBar;
  private File                         m_lastExportDir;
  private Button                       m_clearStatsSessionButton;
  private Button                       m_clearAllStatsSessionsButton;

  static {
    m_connectIcon = new ImageIcon(ServerPanel.class.getResource("/com/tc/admin/icons/disconnect_co.gif"));
    m_disconnectIcon = new ImageIcon(ServerPanel.class.getResource("/com/tc/admin/icons/newex_wiz.gif"));
  }

  public ClusterPanel(ClusterNode clusterNode) {
    super(clusterNode);

    m_clusterNode = clusterNode;
    m_acc = AdminClient.getContext();

    load((ContainerResource) m_acc.topRes.getComponent("ClusterPanel"));

    m_tabbedPane = (TabbedPane) findComponent("TabbedPane");
    m_hostField = (JTextField) findComponent("HostField");
    m_portField = (JTextField) findComponent("PortField");
    m_connectButton = (JButton) findComponent("ConnectButton");
    m_statusView = (StatusView) findComponent("StatusIndicator");
    m_shutdownButton = (JButton) findComponent("ShutdownButton");
    m_productInfoPanel = (ProductInfoPanel) findComponent("ProductInfoPanel");

    m_statusView.setLabel("Not connected");
    m_productInfoPanel.setVisible(false);

    m_hostField.addActionListener(new HostFieldHandler());
    m_portField.addActionListener(new PortFieldHandler());
    m_connectButton.addActionListener(new ConnectionButtonHandler());

    m_hostField.setText(m_clusterNode.getHost());
    m_portField.setText(Integer.toString(m_clusterNode.getPort()));

    m_shutdownButton.setAction(m_clusterNode.getShutdownAction());

    setupConnectButton();

    m_threadDumpButton = (Button) findComponent("TakeThreadDumpButton");
    m_threadDumpButton.addActionListener(new ThreadDumpButtonHandler());

    m_threadDumpTree = (XTree) findComponent("ThreadDumpTree");
    m_threadDumpTree.getSelectionModel().addTreeSelectionListener(new ThreadDumpTreeSelectionListener());

    m_threadDumpTree.setModel(m_threadDumpTreeModel = new XTreeModel());
    m_threadDumpTree.setShowsRootHandles(true);

    m_threadDumpTextArea = (TextArea) findComponent("ThreadDumpTextArea");
    m_threadDumpTextScroller = (ScrollPane) findComponent("ThreadDumpTextScroller");

    m_startGatheringStatsButton = (ToggleButton) findComponent("StartGatheringStatsButton");
    m_startGatheringStatsButton.addActionListener(new StartGatheringStatsAction());

    m_stopGatheringStatsButton = (ToggleButton) findComponent("StopGatheringStatsButton");
    m_stopGatheringStatsButton.addActionListener(new StopGatheringStatsAction());

    m_currentStatsSessionId = null;
    m_statsSessionsList = (List) findComponent("StatsSessionsList");
    m_statsSessionsList.addListSelectionListener(new StatsSessionsListSelectionListener());
    m_statsSessionsList.setModel(m_statsSessionsListModel = new DefaultListModel());
    m_statsConfigPanel = (Container) findComponent("StatsConfigPanel");

    m_exportStatsButton = (Button) findComponent("ExportStatsButton");
    m_exportStatsButton.addActionListener(new ExportStatsHandler());

    m_clearStatsSessionButton = (Button) findComponent("ClearStatsSessionButton");
    m_clearStatsSessionButton.addActionListener(new ClearStatsSessionHandler());

    m_clearAllStatsSessionsButton = (Button) findComponent("ClearAllStatsSessionsButton");
    m_clearAllStatsSessionsButton.addActionListener(new ClearAllStatsSessionsHandler());

    Item exportProgressBarHolder = (Item) findComponent("ExportProgressBarHolder");
    exportProgressBarHolder.add(m_exportProgressBar = new JProgressBar());
    m_exportProgressBar.setVisible(false);
  }

  class HostFieldHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      String host = m_hostField.getText().trim();

      m_clusterNode.setHost(host);
      m_acc.controller.nodeChanged(m_clusterNode);
      m_acc.controller.updateServerPrefs();
    }
  }

  class PortFieldHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      String port = m_portField.getText().trim();

      try {
        m_clusterNode.setPort(Integer.parseInt(port));
        m_acc.controller.nodeChanged(m_clusterNode);
        m_acc.controller.updateServerPrefs();
      } catch (Exception e) {
        Toolkit.getDefaultToolkit().beep();
        m_acc.controller.log("'" + port + "' not a number");
        m_portField.setText(Integer.toString(m_clusterNode.getPort()));
      }
    }
  }

  class ConnectionButtonHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      m_connectButton.setEnabled(false);
      if (m_clusterNode.isConnected()) {
        disconnect();
      } else {
        connect();
      }
    }
  }

  class ThreadDumpButtonHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      try {
        ClusterThreadDumpEntry tde = m_clusterNode.takeThreadDump();
        XTreeNode root = (XTreeNode) m_threadDumpTreeModel.getRoot();
        int index = root.getChildCount();

        root.add(tde);
        // the following is daft; nodesWereInserted is all that should be needed but for some
        // reason the first node requires nodeStructureChanged on the root; why? I don't know.
        m_threadDumpTreeModel.nodesWereInserted(root, new int[] { index });
        m_threadDumpTreeModel.nodeStructureChanged(root);
      } catch (Exception e) {
        m_acc.log(e);
      }
    }
  }

  class ThreadDumpTreeSelectionListener implements TreeSelectionListener {
    public void valueChanged(TreeSelectionEvent e) {
      if (m_lastSelectedThreadDumpTreeNode != null) {
        m_lastSelectedThreadDumpTreeNode.setViewPosition(m_threadDumpTextScroller.getViewport().getViewPosition());
      }
      ThreadDumpTreeNode tdtn = (ThreadDumpTreeNode) m_threadDumpTree.getLastSelectedPathComponent();
      if (tdtn != null) {
        m_threadDumpTextArea.setText(tdtn.getContent());
        final Point viewPosition = tdtn.getViewPosition();
        if (viewPosition != null) {
          SwingUtilities.invokeLater(new Runnable() {
            public void run() {
              m_threadDumpTextScroller.getViewport().setViewPosition(viewPosition);
            }
          });
        }
      }
      m_lastSelectedThreadDumpTreeNode = tdtn;
    }
  }

  private void testSetupStats() {
    if (m_statisticsGathererMBean == null) {
      ConnectionContext cc = m_clusterNode.getConnectionContext();
      m_statisticsGathererMBean = m_clusterNode.getStatisticsGathererMBean();
      try {
        if(m_statsGathererListener == null) {
          m_statsGathererListener = new StatisticsGathererListener();
        }
        cc.addNotificationListener(StatisticsMBeanNames.STATISTICS_GATHERER, m_statsGathererListener);
      } catch(Exception e){
        e.printStackTrace();
      }
      try {
        m_statisticsGathererMBean.connect();
      } catch(Exception e) {
        Throwable cause = e.getCause();
        if(cause instanceof TCStatisticsGathererAlreadyConnectedException) {
          gathererConnected();
        } else {
          e.printStackTrace();
        }
      }
      setupStatsConfigPanel();
    }
  }

  private String[] getAllSessions() {
    return m_statisticsGathererMBean.getAvailableSessionIds();
  }
  
  private void gathererConnected() {
    m_statsSessionsListModel.clear();
    String[] sessions = getAllSessions();
    for(int i = 0; i < sessions.length; i++) {
      m_statsSessionsListModel.addElement(new StatsSessionListItem(sessions[i]));
    }
    m_currentStatsSessionId = m_statisticsGathererMBean.getActiveSessionId();
    boolean gathering = m_currentStatsSessionId != null;
    if(gathering) {
      m_statsGathererListener.showRecordingInProgress();
    }
    m_startGatheringStatsButton.setSelected(gathering);
  }
  
  class StatisticsGathererListener implements NotificationListener {
    private JProgressBar fRecordProgressBar;
    
    JProgressBar getRecordProgressBar() {
      if(fRecordProgressBar == null) {
        fRecordProgressBar = new JProgressBar();
      }
      return fRecordProgressBar;
    }
    
    private void showRecordingInProgress() {
      Container activityArea = AdminClient.getContext().getActivityArea();
      activityArea.setLayout(new BorderLayout());
      activityArea.add(new Label("Recording statistics"), BorderLayout.WEST);
      JProgressBar recordProgressBar = getRecordProgressBar();
      activityArea.add(recordProgressBar);
      recordProgressBar.setForeground(Color.red);
      recordProgressBar.setIndeterminate(true);
      activityArea.revalidate();
      activityArea.repaint();
    }
    
    private void hideRecordingInProgress() {
      Container activityArea = AdminClient.getContext().getActivityArea();
      activityArea.removeAll();
      activityArea.revalidate();
      activityArea.repaint();
    }
    
    public void handleNotification(Notification notification, Object handback) {
      String type = notification.getType();
      Object userData = notification.getUserData();
      
      if(type.equals("tc.statistics.localgatherer.connected")) {
        gathererConnected();
        return;
      }
      
      if(type.equals("tc.statistics.localgatherer.session.created")) {
        m_currentStatsSessionId = (String)userData;
        return;
      }

      if(type.equals("tc.statistics.localgatherer.capturing.started")) {
        showRecordingInProgress();
        return;
      }

      if(type.equals("tc.statistics.localgatherer.capturing.stopped")) {
        String thisSession = (String)userData;
        if(m_currentStatsSessionId != null && m_currentStatsSessionId.equals(thisSession)) {
          m_statsSessionsListModel.addElement(new StatsSessionListItem(thisSession));
          m_statsSessionsList.setSelectedIndex(m_statsSessionsListModel.getSize() - 1);
          m_currentStatsSessionId = null;

          hideRecordingInProgress();
          return;
        }
      }
      
      if(type.equals("tc.statistics.localgatherer.session.cleared")) {
        String sessionId = (String)userData;
        int sessionCount = m_statsSessionsListModel.getSize();
        for(int i = 0; i < sessionCount; i++) {
          StatsSessionListItem item = (StatsSessionListItem)m_statsSessionsListModel.elementAt(i);
          if(sessionId.equals(item.getSessionId())) {
            m_statsSessionsListModel.remove(i);
            break;
          }
        }
        return;
      }
      
      if(type.equals("tc.statistics.localgatherer.allsessions.cleared")) {
        m_statsSessionsListModel.clear();
        m_currentStatsSessionId = null;
        return;
      }
    }
  }
  
  private void tearDownStats() {
    m_statisticsGathererMBean = null;
  }

  private void setupStatsConfigPanel() {
    String[] stats = m_statisticsGathererMBean.getSupportedStatistics();

    m_statsConfigPanel.removeAll();
    m_statsConfigPanel.setLayout(new GridLayout(stats.length, 1));
    if (m_statsControls == null) {
      m_statsControls = new HashMap();
    } else {
      m_statsControls.clear();
    }
    for (String stat : stats) {
      JCheckBox control = new JCheckBox();
      control.setText(stat);
      control.setName(stat);
      m_statsControls.put(stat, control);
      m_statsConfigPanel.add(control);
      control.setSelected(true);
    }
  }

  class StartGatheringStatsAction implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      testSetupStats();
      try {
        m_currentStatsSessionId = new Date().toString();
        m_statisticsGathererMBean.createSession(m_currentStatsSessionId);
        Iterator iter = m_statsControls.keySet().iterator();
        ArrayList<String> statList = new ArrayList<String>();
        while (iter.hasNext()) {
          String stat = (String) iter.next();
          JCheckBox control = (JCheckBox) m_statsControls.get(stat);
          if (control.isSelected()) {
            statList.add(stat);
          }
        }
        m_statisticsGathererMBean.enableStatistics(statList.toArray(new String[0]));
        m_statisticsGathererMBean.startCapturing();
      } catch (Exception e) {
        e.printStackTrace();
      }
      m_stopGatheringStatsButton.setSelected(false);
    }
  }

  class StopGatheringStatsAction implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      m_startGatheringStatsButton.setSelected(false);
      try {
        m_statisticsGathererMBean.stopCapturing();
      } catch (Exception e) {
        AdminClient.getContext().log(e);
      }
    }
  }

  class StatsSessionsListSelectionListener implements ListSelectionListener {
    public void valueChanged(ListSelectionEvent e) {
      boolean haveSelectedSession = getSelectedSessionId() != null;
      m_exportStatsButton.setEnabled(haveSelectedSession);
      m_clearStatsSessionButton.setEnabled(haveSelectedSession);
      m_clearAllStatsSessionsButton.setEnabled(m_statsSessionsListModel.getSize() > 0);
    }
  }

  class StatsSessionListItem {
    private String fSessionId;

    StatsSessionListItem(String sessionId) {
      fSessionId = sessionId;
    }

    String getSessionId() {
      return fSessionId;
    }

    public String toString() {
      return fSessionId;
    }
  }

  String getSelectedSessionId() {
    StatsSessionListItem item = (StatsSessionListItem) m_statsSessionsList.getSelectedValue();
    return item != null ? item.getSessionId() : null;
  }

  class ExportStatsHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      JFileChooser chooser = new JFileChooser();
      if (m_lastExportDir != null) chooser.setCurrentDirectory(m_lastExportDir);
      if (m_statsSessionsListModel.getSize() == 0) return;
      chooser.setDialogTitle("Export statistics");
      chooser.setMultiSelectionEnabled(false);
      if (chooser.showSaveDialog(ClusterPanel.this) != JFileChooser.APPROVE_OPTION) return;
      File file = chooser.getSelectedFile();
      m_lastExportDir = file.getParentFile();
      GetMethod get = null;
      try {
        String uri = m_clusterNode.getStatsExportServletURI();
        URL url = new URL(uri);
        HttpClient httpClient = new HttpClient();

        get = new GetMethod(url.toString());
        get.setFollowRedirects(true);
        int status = httpClient.executeMethod(get);
        if (status != HttpStatus.SC_OK) {
          AdminClient.getContext().log(
                                       "The http client has encountered a status code other than ok for the url: "
                                           + url + " status: " + HttpStatus.getStatusText(status));
          return;
        }
        m_exportProgressBar.setVisible(true);
        new Thread(new StreamCopierRunnable(get, file)).start();
      } catch (Exception e) {
        AdminClient.getContext().log(e);
        if (get != null) {
          get.releaseConnection();
        }
      }
    }
  }

  class StreamCopierRunnable implements Runnable {
    GetMethod fGetMethod;
    File      fOutFile;

    StreamCopierRunnable(GetMethod getMethod, File outFile) {
      fGetMethod = getMethod;
      fOutFile = outFile;
    }

    public void run() {
      FileOutputStream out = null;

      try {
        out = new FileOutputStream(fOutFile);
        InputStream in = fGetMethod.getResponseBodyAsStream();

        m_exportProgressBar.setIndeterminate(true);
        byte[] buffer = new byte[1024 * 8];
        int count;
        try {
          while ((count = in.read(buffer)) >= 0) {
            out.write(buffer, 0, count);
          }
        } finally {
          SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
              m_exportProgressBar.setVisible(false);
              AdminClient.getContext().setStatus("Wrote '" + fOutFile.getAbsolutePath() + "'");
            }
          });
          IOUtils.closeQuietly(in);
          IOUtils.closeQuietly(out);
        }
      } catch (Exception e) {
        AdminClient.getContext().log(e);
      } finally {
        IOUtils.closeQuietly(out);
        fGetMethod.releaseConnection();
      }
    }
  }

  class ClearStatsSessionHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      StatsSessionListItem item = (StatsSessionListItem) m_statsSessionsList.getSelectedValue();
      m_statisticsGathererMBean.clearStatistics(item.getSessionId());
    }
  }

  class ClearAllStatsSessionsHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      m_statisticsGathererMBean.clearAllStatistics();
    }
  }

  void setupConnectButton() {
    String label;
    Icon icon;
    boolean enabled;
    boolean connected = m_clusterNode.isConnected();

    if (connected) {
      label = "Disconnect";
      icon = m_disconnectIcon;
      enabled = true;
    } else {
      label = "Connect...";
      icon = m_connectIcon;
      enabled = !m_clusterNode.isAutoConnect();
    }

    m_connectButton.setText(label);
    m_connectButton.setIcon(icon);
    m_connectButton.setEnabled(enabled);

    setTabbedPaneEnabled(connected);
  }

  JButton getConnectButton() {
    return m_connectButton;
  }

  private void connect() {
    m_clusterNode.connect();
  }

  void activated() {
    m_hostField.setEditable(false);
    m_portField.setEditable(false);

    setupConnectButton();

    Date activateDate = new Date(m_clusterNode.getActivateTime());
    String activateTime = activateDate.toString();

    setStatusLabel(m_acc.format("server.activated.label", new Object[] { activateTime }));
    if (!isProductInfoShowing()) {
      showProductInfo();
    }

    testSetupStats();

    m_acc.controller.setStatus(m_acc.format("server.activated.status", new Object[] { m_clusterNode, activateTime }));
  }

  /**
   * The only differences between activated() and started() is the status message and the serverlog is only added in
   * activated() under the presumption that a non-active server won't be saying anything.
   */
  void started() {
    m_hostField.setEditable(false);
    m_portField.setEditable(false);

    Date startDate = new Date(m_clusterNode.getStartTime());
    String startTime = startDate.toString();

    setupConnectButton();
    setStatusLabel(m_acc.format("server.started.label", new Object[] { startTime }));
    if (!isProductInfoShowing()) {
      showProductInfo();
    }

    // testSetupStats();

    m_acc.controller.setStatus(m_acc.format("server.started.status", new Object[] { m_clusterNode, startTime }));
  }

  void passiveUninitialized() {
    m_hostField.setEditable(false);
    m_portField.setEditable(false);

    String startTime = new Date().toString();

    setupConnectButton();
    setStatusLabel(m_acc.format("server.initializing.label", new Object[] { startTime }));
    if (!isProductInfoShowing()) {
      showProductInfo();
    }

    // testSetupStats();

    m_acc.controller.setStatus(m_acc.format("server.initializing.status", new Object[] { m_clusterNode, startTime }));
  }

  void passiveStandby() {
    m_hostField.setEditable(false);
    m_portField.setEditable(false);

    String startTime = new Date().toString();

    setupConnectButton();
    setStatusLabel(m_acc.format("server.standingby.label", new Object[] { startTime }));
    if (!isProductInfoShowing()) {
      showProductInfo();
    }

    // testSetupStats();

    m_acc.controller.setStatus(m_acc.format("server.standingby.status", new Object[] { m_clusterNode, startTime }));
  }

  private void disconnect() {
    m_clusterNode.getDisconnectAction().actionPerformed(null);
  }

  private void setTabbedPaneEnabled(boolean enabled) {
    m_tabbedPane.setEnabled(enabled);
    int tabCount = m_tabbedPane.getTabCount();
    for (int i = 1; i < tabCount; i++) {
      m_tabbedPane.setEnabledAt(i, enabled);
    }
    m_tabbedPane.setSelectedIndex(0);
  }

  void disconnected() {
    m_hostField.setEditable(true);
    m_portField.setEditable(true);

    String startTime = new Date().toString();

    setupConnectButton();
    setStatusLabel(m_acc.format("server.disconnected.label", new Object[] { startTime }));
    hideRuntimeInfo();
    tearDownStats();
    if(m_currentStatsSessionId != null) {
      m_statsGathererListener.hideRecordingInProgress();
    }
    m_acc.controller.setStatus(m_acc.format("server.disconnected.status", new Object[] { m_clusterNode, startTime }));
  }

  void setStatusLabel(String msg) {
    m_statusView.setLabel(msg);
    m_statusView.setIndicator(m_clusterNode.getServerStatusColor());
  }

  boolean isProductInfoShowing() {
    return m_productInfoPanel.isVisible();
  }

  private void showProductInfo() {
    m_productInfoPanel.init(m_clusterNode.getProductInfo());
    m_productInfoPanel.setVisible(true);

    revalidate();
    repaint();
  }

  private void hideRuntimeInfo() {
    m_productInfoPanel.setVisible(false);
    revalidate();
    repaint();
  }

  public void tearDown() {
    super.tearDown();

    m_statusView.tearDown();
    m_productInfoPanel.tearDown();

    m_acc = null;
    m_clusterNode = null;
    m_hostField = null;
    m_portField = null;
    m_connectButton = null;
    m_statusView = null;
    m_productInfoPanel = null;
  }
}
