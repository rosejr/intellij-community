// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.zmlx.hg4idea.status.ui;

import com.intellij.dvcs.repo.VcsRepositoryManager;
import com.intellij.dvcs.repo.VcsRepositoryMappingListener;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.CalledInAny;
import org.jetbrains.annotations.NotNull;
import org.zmlx.hg4idea.HgBundle;
import org.zmlx.hg4idea.HgVcs;
import org.zmlx.hg4idea.status.HgChangesetStatus;
import org.zmlx.hg4idea.status.HgRemoteStatusUpdater;

import javax.swing.*;
import java.awt.event.MouseEvent;
import java.util.Objects;

final class HgIncomingOutgoingWidget extends EditorBasedWidget implements StatusBarWidget.IconPresentation, StatusBarWidget.Multiframe {
  private static final String INCOMING_WIDGET_ID = "InHgIncomingOutgoingWidget";
  private static final String OUTGOING_WIDGET_ID = "OutHgIncomingOutgoingWidget";

  private final @NotNull HgVcs myVcs;
  private final boolean myIsIncoming;
  private final @NotNull Icon myEnabledIcon;
  private final @NotNull Icon myDisabledIcon;
  private volatile @NlsContexts.Tooltip String myTooltip = "";
  private Icon myCurrentIcon;

  HgIncomingOutgoingWidget(@NotNull HgVcs vcs, boolean isIncoming) {
    super(vcs.getProject());
    myIsIncoming = isIncoming;
    myVcs = vcs;
    myEnabledIcon = myIsIncoming ? AllIcons.Ide.IncomingChangesOn : AllIcons.Ide.OutgoingChangesOn;
    myDisabledIcon = IconLoader.getDisabledIcon(myEnabledIcon);
    myCurrentIcon = myDisabledIcon;

    updateLater();
  }

  @Override
  public void install(@NotNull StatusBar statusBar) {
    super.install(statusBar);

    myConnection.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, () -> updateLater());
    myConnection.subscribe(HgVcs.STATUS_TOPIC, (project, root) -> updateLater());
    myConnection.subscribe(HgVcs.REMOTE_TOPIC, (project, root) -> updateLater());
    myConnection.subscribe(HgVcs.INCOMING_OUTGOING_CHECK_TOPIC, new HgWidgetUpdater() {
      @Override
      public void update() {
        updateLater();
      }
    });

    myConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
      @Override
      public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        updateLater();
      }

      @Override
      public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        updateLater();
      }

      @Override
      public void selectionChanged(@NotNull FileEditorManagerEvent event) {
        updateLater();
      }
    });
  }

  @Override
  public StatusBarWidget copy() {
    return new HgIncomingOutgoingWidget(myVcs, myIsIncoming);
  }

  @Override
  public @NotNull String ID() {
    return myIsIncoming ? INCOMING_WIDGET_ID : OUTGOING_WIDGET_ID;
  }

  @Override
  public WidgetPresentation getPresentation() {
    return this;
  }

  @Override
  public String getTooltipText() {
    return myTooltip;
  }

  @Override
  // Updates branch information on click
  public Consumer<MouseEvent> getClickConsumer() {
    return mouseEvent -> updateLater();
  }

  @CalledInAny
  public void updateLater() {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (Disposer.isDisposed(this)) return;

      HgRemoteStatusUpdater statusUpdater = myVcs.getRemoteStatusUpdater();
      if (statusUpdater == null) return;
      HgChangesetStatus status = statusUpdater.getStatus(myIsIncoming);
      boolean changesAvailable = status.getNumChanges() > 0;
      myCurrentIcon = changesAvailable ? myEnabledIcon : myDisabledIcon;
      myTooltip = changesAvailable ? "\n" + status.getToolTip() : HgBundle.message("no.changes.available");
      if (myStatusBar != null) myStatusBar.updateWidget(ID());
    });
  }

  @Override
  public @NotNull Icon getIcon() {
    return myCurrentIcon;
  }

  // if smb call hide widget then it removed from status bar and dispose method called.
  // if we do not override dispose IDE call EditorWidget dispose method and set connection to null.
  // next, if we repeat hide/show dispose eth will be callees several times,but connection will be null -> NPE or already disposed message.
  @Override
  public void dispose() {
    if (!isDisposed()) {
      super.dispose();
    }
  }

  public static class Listener implements VcsRepositoryMappingListener, HgWidgetUpdater {
    private final Project myProject;

    public Listener(@NotNull Project project) {
      myProject = project;
    }

    @Override
    public void mappingChanged() {
      updateVisibility();
    }

    @Override
    public void updateVisibility() {
      StatusBarWidgetsManager widgetsManager = myProject.getService(StatusBarWidgetsManager.class);
      widgetsManager.updateWidget(HgIncomingOutgoingWidget.IncomingFactory.class);
      widgetsManager.updateWidget(HgIncomingOutgoingWidget.OutgoingFactory.class);
    }
  }

  public static class IncomingFactory extends MyWidgetFactory {
    public IncomingFactory() {
      super(true);
    }
  }

  public static class OutgoingFactory extends MyWidgetFactory {
    public OutgoingFactory() {
      super(false);
    }
  }

  private abstract static class MyWidgetFactory implements StatusBarWidgetFactory {
    private final boolean myIsIncoming;

    protected MyWidgetFactory(boolean isIncoming) {
      myIsIncoming = isIncoming;
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
      return HgRemoteStatusUpdater.isCheckingEnabled(project);
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
      HgVcs hgVcs = Objects.requireNonNull(HgVcs.getInstance(project));
      return new HgIncomingOutgoingWidget(hgVcs, myIsIncoming);
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
      return true;
    }

    @Override
    public @NotNull String getId() {
      return myIsIncoming ? INCOMING_WIDGET_ID : OUTGOING_WIDGET_ID;
    }

    @Override
    public @NotNull String getDisplayName() {
      return myIsIncoming
             ? HgBundle.message("hg4idea.status.bar.incoming.widget.name")
             : HgBundle.message("hg4idea.status.bar.outgoing.widget.name");
    }
  }
}
