/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2016 Serge Rieder (serge@jkiss.org)
 * Copyright (C) 2011-2012 Eugene Fradkin (eugene.fradkin@gmail.com)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (version 2)
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jkiss.dbeaver.ui.controls.resultset;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ControlEditor;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.progress.UIJob;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.CoreMessages;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.ProxyProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.ui.AbstractUIJob;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.utils.CommonUtils;

class ResultSetJobDataRead extends ResultSetJobAbstract {

    private static final Log log = Log.getLog(ResultSetJobDataRead.class);

    private DBDDataFilter dataFilter;
    private Composite progressControl;
    private int offset;
    private int maxRows;
    private Throwable error;
    private DBCStatistics statistics;
    private long pumpStartTime;
    private String progressMessage;
    private final int forceCancelTimeout;

    protected ResultSetJobDataRead(DBSDataContainer dataContainer, DBDDataFilter dataFilter, ResultSetViewer controller, DBCExecutionContext executionContext, Composite progressControl) {
        super(CoreMessages.controls_rs_pump_job_name + " [" + dataContainer + "]", dataContainer, controller, executionContext);
        this.dataFilter = dataFilter;
        this.progressMessage = CoreMessages.controls_rs_pump_job_name;
        this.progressControl = progressControl;
        this.forceCancelTimeout = dataContainer.getDataSource().getContainer().getPreferenceStore().getInt(DBeaverPreferences.RESULT_SET_CANCEL_TIMEOUT);
    }

    public void setOffset(int offset)
    {
        this.offset = offset;
    }

    public void setMaxRows(int maxRows)
    {
        this.maxRows = maxRows;
    }

    public Throwable getError()
    {
        return error;
    }

    DBCStatistics getStatistics()
    {
        return statistics;
    }

    @Override
    protected IStatus run(DBRProgressMonitor monitor) {
        error = null;
        pumpStartTime = System.currentTimeMillis();
        DBRProgressMonitor proxyMonitor = new ProxyProgressMonitor(monitor) {
            @Override
            public void beginTask(String name, int totalWork) {
                //progressMessage = name;
                super.beginTask(name, totalWork);
            }

            @Override
            public void subTask(String name) {
                progressMessage = name;
                super.subTask(name);
            }
        };
        PumpVisualizer visualizer = new PumpVisualizer();
        DBCExecutionPurpose purpose = DBCExecutionPurpose.USER;
        if (dataFilter != null && dataFilter.hasFilters()) {
            purpose = DBCExecutionPurpose.USER_FILTERED;
        }
        try (DBCSession session = getExecutionContext().openSession(
            proxyMonitor,
            purpose,
            NLS.bind(CoreMessages.controls_rs_pump_job_context_name, dataContainer.toString())))
        {
            visualizer.schedule(PROGRESS_VISUALIZE_PERIOD * 2);
            statistics = dataContainer.readData(
                this,
                session,
                controller.getDataReceiver(),
                dataFilter,
                offset,
                maxRows,
                DBSDataContainer.FLAG_READ_PSEUDO
            );
        } catch (DBException e) {
            error = e;
        } finally {
            visualizer.finished = true;
        }

        return Status.OK_STATUS;
    }

    private class PumpVisualizer extends UIJob {

        private volatile boolean finished = false;
        private ControlEditor progressOverlay;
        private volatile int drawCount = 0;
        private Button cancelButton;
        private PaintListener painListener;
        private Color shadowColor;

        public PumpVisualizer() {
            super(progressControl.getDisplay(), "RSV Pump Visualizer");
            setSystem(true);
        }

        @Override
        public IStatus runInUIThread(IProgressMonitor monitor) {
            if (!progressControl.isDisposed()) {
                if (shadowColor == null) {
                    shadowColor = progressControl.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW);
                }
                if (!finished) {
                    try {
                        showProgress();
                    } catch (Exception e) {
                        log.error("Internal error during progress visualization", e);
                        // Something went terribly wrong
                        // We shouldn't be here ever. In any case we must finish the job
                        finishProgress();
                    }
                }
                if (finished) {
                    finishProgress();
                }
            }
            return Status.OK_STATUS;
        }

        private void showProgress() {
            if (progressOverlay == null) {
                // Start progress visualization
                cancelButton = new Button(progressControl, SWT.PUSH);
                cancelButton.setText("Cancel");
                GridData gd = new GridData(GridData.FILL_BOTH);
                gd.verticalIndent = DBeaverIcons.getImage(UIIcon.PROGRESS0).getBounds().height * 2;
                cancelButton.setLayoutData(gd);
                cancelButton.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        cancelButton.setText("Canceled");
                        cancelButton.setEnabled(false);
                        Point buttonSize = cancelButton.computeSize(SWT.DEFAULT, SWT.DEFAULT);
                        progressOverlay.minimumWidth = buttonSize.x;
                        progressOverlay.minimumHeight = buttonSize.y;
                        progressOverlay.layout();
                        ResultSetJobDataRead.this.cancel();
                        if (forceCancelTimeout > 0) {
                            new ForceCancelJob().schedule(forceCancelTimeout);
                        }
                    }
                });

                painListener = new PaintListener() {
                    @Override
                    public void paintControl(PaintEvent e) {
                        Image image = DBeaverIcons.getImage(PROGRESS_IMAGES[drawCount % PROGRESS_IMAGES.length]);
                        Rectangle buttonBounds = cancelButton.getBounds();
                        Rectangle imageBounds = image.getBounds();
                        e.gc.drawImage(
                                image,
                                (buttonBounds.x + buttonBounds.width / 2) - imageBounds.width / 2,
                                buttonBounds.y - imageBounds.height - 5);

                        long elapsedTime = System.currentTimeMillis() - pumpStartTime;
                        String elapsedString = elapsedTime > 10000 ?
                                String.valueOf(elapsedTime / 1000) :
                                String.valueOf(((double) (elapsedTime / 100)) / 10);
                        String statusMessage = CommonUtils.truncateString(
                                progressMessage.replaceAll("\\s", " "), 64);
                        String status = statusMessage + " - " + elapsedString + "s";
                        Point statusSize = e.gc.textExtent(status);

                        int statusX = (buttonBounds.x + buttonBounds.width / 2) - statusSize.x / 2;
                        int statusY = buttonBounds.y - imageBounds.height - 10 - statusSize.y;
                        e.gc.setForeground(controller.getDefaultForeground());
                        e.gc.setBackground(controller.getDefaultBackground());
                        e.gc.fillRectangle(statusX - 2, statusY - 2, statusSize.x + 4, statusSize.y + 4);
                        e.gc.drawText(status, statusX, statusY, true);
                        e.gc.setForeground(shadowColor);
                        e.gc.drawRoundRectangle(statusX - 3, statusY - 3, statusSize.x + 5, statusSize.y + 5, 5, 5);
                    }
                };
                progressControl.addPaintListener(painListener);

                progressOverlay = new ControlEditor(progressControl);
                Point buttonSize = cancelButton.computeSize(SWT.DEFAULT, SWT.DEFAULT);
                progressOverlay.minimumWidth = buttonSize.x;
                progressOverlay.minimumHeight = buttonSize.y;
                progressOverlay.setEditor(cancelButton);
            }
            drawCount++;
            progressOverlay.layout();
            progressControl.redraw();
            schedule(PROGRESS_VISUALIZE_PERIOD);
        }

        private void finishProgress() {
                // Last update - remove progress visualization
            if (progressOverlay != null) {
                if (!progressControl.isDisposed()) {
                    progressControl.removePaintListener(painListener);
                    progressOverlay.dispose();
                }
                progressOverlay = null;
                if (!cancelButton.isDisposed()) {
                    cancelButton.dispose();
                }
                progressControl.redraw();
            }
        }

        private class ForceCancelJob extends AbstractUIJob {
            protected ForceCancelJob() {
                super("Force RSV cancel");
            }

            @Override
            protected IStatus runInUIThread(DBRProgressMonitor monitor) {
                // TODO: to something
/*
                if (!finished) {
                    error = new DBException("Query cancellation timeout. Query processing was terminated in UI.");
                    finished = true;
                    finishProgress();
                }
*/
                return Status.OK_STATUS;
            }
        }

    }

}
