/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package com.tc.admin.dso.trace;

import com.tc.admin.IAdminClientContext;
import com.tc.admin.common.BasicWorker;
import com.tc.admin.common.ExceptionHelper;
import com.tc.admin.common.XButton;
import com.tc.admin.common.XContainer;
import com.tc.admin.common.XLabel;
import com.tc.admin.common.XObjectTable;
import com.tc.admin.common.XScrollPane;
import com.tc.admin.common.XTextArea;
import com.tc.admin.common.XTextField;
import com.tc.admin.model.IClient;
import com.tc.admin.model.IClusterModel;
import com.tc.statistics.StatisticData;
import com.tc.statistics.beans.StatisticsMBeanNames;
import com.tc.statistics.beans.StatisticsManagerMBean;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.management.ObjectName;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;

public class ClientMethodTracingPanel extends XContainer {
  private IClient                 client;
  private IAdminClientContext     adminClientContext;
  private MethodTracesGetter      currentTraceResultsGetter;
  private XObjectTable            methodTracingTable;
  private MethodTracingTableModel methodTracingTableModel;
  private XTextField              traceMethodInput;
  private StatisticsManagerMBean  statsBean;
  private XTextArea               onEntryScriptArea;
  private XTextArea               onExitScriptArea;

  private static final int        REFRESH_TIMEOUT_SECONDS = Integer.MAX_VALUE;

  public ClientMethodTracingPanel(IClusterModel clusterModel, IClient client, IAdminClientContext adminClientContext) {
    super(new BorderLayout());

    this.adminClientContext = adminClientContext;
    this.client = client;
    
    XContainer topPanel = new XContainer(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = gbc.gridy = 0;
    gbc.insets = new Insets(3, 3, 3, 3);

    topPanel.add(new XLabel("Method to trace:"), gbc);
    gbc.gridx++;

    topPanel.add(traceMethodInput = new XTextField("org.mypackage.MyClass.method(Ljava/lang/String;)Z"), gbc);
    gbc.gridx++;

    XButton addButton = new XButton("Add Tracer");
    topPanel.add(addButton, gbc);
    gbc.gridx++;
    addButton.addActionListener(new AddButtonHandler());

    XButton refreshButton = new XButton("Refresh All");
    topPanel.add(refreshButton, gbc);
    refreshButton.addActionListener(new RefreshButtonHandler());
    add(topPanel, BorderLayout.NORTH);

    XContainer bshPanel = new XContainer(new GridBagLayout());
    gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.BOTH;
    gbc.weightx = 1.0;
    gbc.weighty = 1.0;
    gbc.gridx = gbc.gridy = 0;
    bshPanel.add(new XLabel("BeanShell OnEntry Script"), gbc);
    gbc.gridx++;
    bshPanel.add(new XLabel("BeanShell OnExit Script"), gbc);

    gbc.gridy++;
    
    gbc.gridx = 0;
    onEntryScriptArea = new XTextArea();
    onEntryScriptArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
    bshPanel.add(new XScrollPane(onEntryScriptArea), gbc);

    gbc.gridx++;
    onExitScriptArea = new XTextArea();
    onExitScriptArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
    bshPanel.add(new XScrollPane(onExitScriptArea), gbc);

    add(bshPanel, BorderLayout.CENTER);
    
    TableMouseListener tableMouseListener = new TableMouseListener();
    TableKeyListener tableKeyListener = new TableKeyListener();
    
    XContainer methodTracingPanel = new XContainer(new BorderLayout());
    methodTracingTable = new MethodTracingTable();
    methodTracingTableModel = new MethodTracingTableModel(adminClientContext);
    methodTracingTable.setModel(methodTracingTableModel);
    methodTracingTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    methodTracingTable.addMouseListener(tableMouseListener);
    methodTracingTable.addKeyListener(tableKeyListener);
    methodTracingPanel.add(new XScrollPane(methodTracingTable));

    add(methodTracingPanel, BorderLayout.SOUTH);
    
    ObjectName on = client.getTunneledBeanName(StatisticsMBeanNames.STATISTICS_MANAGER);
    statsBean = clusterModel.getActiveCoordinator().getMBeanProxy(on, StatisticsManagerMBean.class);
    
    if (!client.getTracingManagerBean().isMethodTracingEnabled()) {
      traceMethodInput.setEnabled(false);
      addButton.setEnabled(false);
      refreshButton.setEnabled(false);
      onEntryScriptArea.setEnabled(false);
      onExitScriptArea.setEnabled(false);
    }
  }

  private class TableMouseListener extends MouseAdapter {
    @Override
    public void mousePressed(MouseEvent e) {
      JTable table = (JTable) e.getSource();
      int row = table.rowAtPoint(e.getPoint());
      if (row != -1) {
        table.setRowSelectionInterval(row, row);
      }
    }
  }

  private class TableKeyListener extends KeyAdapter {
    @Override
    public void keyReleased(KeyEvent e) {
      if ((e.getKeyCode() == KeyEvent.VK_BACK_SPACE) || (e.getKeyCode() == KeyEvent.VK_DELETE)) {
        JTable table = (JTable) e.getSource();
        for (int i : table.getSelectedRows()) {
          String methodName = (String) table.getModel().getValueAt(i, 0);
          methodName = methodName.split("@")[0];
          int separator = methodName.lastIndexOf('.');
          
          try {
            client.getTracingManagerBean().stopTracingMethod(methodName.substring(0, separator), methodName.substring(separator + 1));
          } catch (Exception ex) {
            adminClientContext.log("Exception while stopping instrumentation of " + methodName + " [" + ex + "]");
          }
        }
        refresh();
      }
    }
  }

  void refresh() {
    if (currentTraceResultsGetter != null) {
      currentTraceResultsGetter.cancel(true);
      currentTraceResultsGetter = null;
    } else {
      currentTraceResultsGetter = new MethodTracesGetter();
      adminClientContext.submit(currentTraceResultsGetter);
    }
  }

  private static Map<String, Map<String, Object>> collateStatisticData(StatisticData[] data) {
    Map<String, Map<String, Object>> temp = new HashMap();
    
    for (StatisticData datum : data) {
      Map<String, Object> elements = temp.get(datum.getName());
      if (elements == null) {
        elements = new HashMap();
        temp.put(datum.getName(), elements);
      }
      elements.put(datum.getElement(), datum.getData());
    }
    
    return temp;
  }
  
  class MethodTracesGetter extends BasicWorker<Collection<MethodTraceResult>> {

    MethodTracesGetter() {
      super(new Callable<Collection<MethodTraceResult>>() {
        public Collection<MethodTraceResult> call() throws Exception {
          //Do code to get stuff here
          StatisticData[] data = statsBean.retrieveStatisticData("tracing data");
          
          Collection<MethodTraceResult> traces = new ArrayList<MethodTraceResult>();          
          for (Map.Entry<String, Map<String, Object>> trace : collateStatisticData(data).entrySet()) {
            Map<String, Object> elements = trace.getValue();
            MethodTraceResult tr = new MethodTraceResult(trace.getKey(),
                                                         ((Long) elements.get("execution count")).longValue(),
                                                         ((Long) elements.get("total time")).longValue(),
                                                         ((Long) elements.get("normal exits")).longValue(),
                                                         ((Long) elements.get("exceptional exits")).longValue());
            traces.add(tr);
          }
          
          return traces;
        }
      }, REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    protected void finished() {
      currentTraceResultsGetter = null;

      Exception e = getException();
      if (e != null) {
        String msg;
        Throwable rootCause = ExceptionHelper.getRootCause(e);
        if (rootCause instanceof IOException) {
          return;
        } else if (rootCause instanceof TimeoutException) {
          msg = "timed-out after '" + REFRESH_TIMEOUT_SECONDS + "' seconds";
        } else if (rootCause instanceof CancellationException) {
          msg = "cancelled";
        } else {
          msg = rootCause.getMessage();
        }
        adminClientContext.log(new Date() + ": Method tracing: failed to refresh: " + msg);
      } else {
        Collection<MethodTraceResult> traceResults = getResult();

        methodTracingTable.setModel(methodTracingTableModel = new MethodTracingTableModel(adminClientContext, traceResults));
        methodTracingTable.sort();
      }
    }
  }

  @Override
  public synchronized void tearDown() {
    super.tearDown();

    adminClientContext = null;
    methodTracingTable = null;
    methodTracingTableModel = null;
  }
  
  private class RefreshButtonHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      refresh();
    }
  }
  
  private class AddButtonHandler implements ActionListener {
    public void actionPerformed(ActionEvent ae) {
      String methodName = traceMethodInput.getText();
      int separator = methodName.lastIndexOf('.');

      String bshOnEntry = onEntryScriptArea.getText();
      String bshOnExit = onExitScriptArea.getText();

      try {
        if ((bshOnEntry.length() == 0) && (bshOnExit.length() == 0)) {
          client.getTracingManagerBean().startTracingMethod(methodName.substring(0, separator),
                                                            methodName.substring(separator + 1));
        } else {
          client.getTracingManagerBean().startTracingMethodWithBeanShell(methodName.substring(0, separator),
                                                                         methodName.substring(separator + 1),
                                                                         bshOnEntry, bshOnExit);
        }
      } catch (Exception e) {
        adminClientContext.log("Exception while starting instrumentation of " + methodName + " [" + e + "]");
      }
      refresh();
    }
  }
}