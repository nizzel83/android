/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.profilers.network;

import com.android.tools.adtui.*;
import com.android.tools.adtui.chart.linechart.LineChart;
import com.android.tools.adtui.chart.linechart.LineConfig;
import com.android.tools.adtui.common.formatter.BaseAxisFormatter;
import com.android.tools.adtui.common.formatter.NetworkTrafficFormatter;
import com.android.tools.adtui.model.Range;
import com.android.tools.adtui.model.RangedContinuousSeries;
import com.android.tools.profilers.ProfilerColors;
import com.android.tools.profilers.ProfilerMonitorView;
import com.intellij.ui.components.JBPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;

import static com.android.tools.profilers.ProfilerLayout.*;

public class NetworkMonitorView extends ProfilerMonitorView<NetworkMonitor> {

  private static final BaseAxisFormatter BANDWIDTH_AXIS_FORMATTER_L1 = new NetworkTrafficFormatter(1, 2, 5);

  public NetworkMonitorView(@NotNull NetworkMonitor monitor) {
    super(monitor);
  }

  @Override
  protected void populateUi(JPanel container, Choreographer choreographer) {
    container.setLayout(new TabularLayout("*", "*"));

    Range viewRange = getMonitor().getTimeline().getViewRange();
    Range dataRange = getMonitor().getTimeline().getDataRange();

    final JLabel label = new JLabel(getMonitor().getName());
    label.setBorder(MONITOR_LABEL_PADDING);
    label.setVerticalAlignment(JLabel.TOP);

    Range leftYRange = new Range(0, 4);
    final JPanel axisPanel = new JBPanel(new BorderLayout());
    axisPanel.setOpaque(false);
    AxisComponent.Builder builder = new AxisComponent.Builder(leftYRange, BANDWIDTH_AXIS_FORMATTER_L1,
                                                              AxisComponent.AxisOrientation.RIGHT)
      .showAxisLine(false)
      .showMax(true)
      .showUnitAtMax(true)
      .setMarkerLengths(MARKER_LENGTH, MARKER_LENGTH)
      .clampToMajorTicks(true).setMargins(0, Y_AXIS_TOP_MARGIN);
    final AxisComponent leftAxis = builder.build();
    axisPanel.add(leftAxis, BorderLayout.WEST);

    final JPanel lineChartPanel = new JBPanel(new BorderLayout());
    lineChartPanel.setOpaque(false);
    lineChartPanel.setBorder(BorderFactory.createEmptyBorder(Y_AXIS_TOP_MARGIN, 0, 0, 0));
    final LineChart lineChart = new LineChart();
    RangedContinuousSeries rxSeries = new RangedContinuousSeries(NetworkTrafficDataSeries.Type.BYTES_RECEIVED.getLabel(),
                                                                 viewRange,
                                                                 leftYRange,
                                                                 getMonitor().getSpeedSeries(NetworkTrafficDataSeries.Type.BYTES_RECEIVED));
    RangedContinuousSeries txSeries = new RangedContinuousSeries(NetworkTrafficDataSeries.Type.BYTES_SENT.getLabel(),
                                                                 viewRange,
                                                                 leftYRange,
                                                                 getMonitor().getSpeedSeries(NetworkTrafficDataSeries.Type.BYTES_SENT));
    LineConfig receivedConfig = new LineConfig(ProfilerColors.NETWORK_RECEIVING_COLOR);
    lineChart.addLine(rxSeries, receivedConfig);
    LineConfig sentConfig = new LineConfig(ProfilerColors.NETWORK_SENDING_COLOR);
    lineChart.addLine(txSeries, sentConfig);
    lineChartPanel.add(lineChart, BorderLayout.CENTER);

    final LegendComponent legend = new LegendComponent(LegendComponent.Orientation.HORIZONTAL, LEGEND_UPDATE_FREQUENCY_MS);
    ArrayList<LegendRenderData> legendData = new ArrayList<>();
    legendData.add(lineChart.createLegendRenderData(rxSeries, BANDWIDTH_AXIS_FORMATTER_L1, dataRange));
    legendData.add(lineChart.createLegendRenderData(txSeries, BANDWIDTH_AXIS_FORMATTER_L1, dataRange));
    legend.setLegendData(legendData);

    final JPanel legendPanel = new JBPanel(new BorderLayout());
    legendPanel.setOpaque(false);
    legendPanel.add(label, BorderLayout.WEST);
    legendPanel.add(legend, BorderLayout.EAST);

    choreographer.register(lineChart);
    choreographer.register(leftAxis);
    choreographer.register(legend);

    container.add(legendPanel, new TabularLayout.Constraint(0, 0));
    container.add(leftAxis, new TabularLayout.Constraint(0, 0));
    container.add(lineChartPanel, new TabularLayout.Constraint(0, 0));
    container.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseReleased(MouseEvent e) {
        getMonitor().expand();
      }
    });
  }
}
