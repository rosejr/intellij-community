// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.treeStructure;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.ide.util.treeView.PresentableNodeDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.tree.LeafState;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.update.ComparableObject;
import com.intellij.util.ui.update.ComparableObjectCheck;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class SimpleNode extends PresentableNodeDescriptor<Object> implements ComparableObject, LeafState.Supplier {

  protected static final SimpleNode[] NO_CHILDREN = new SimpleNode[0];

  protected SimpleNode(Project project) {
    this(project, null);
  }

  protected SimpleNode(Project project, @Nullable NodeDescriptor parentDescriptor) {
    super(project, parentDescriptor);
    myName = "";
  }

  protected SimpleNode(SimpleNode parent) {
    this(parent == null ? null : parent.myProject, parent);
  }

  @Override
  public SimpleNode getChildToHighlightAt(int index) {
    return getChildAt(index);
  }

  protected SimpleNode() {
    super(null, null);
  }

  @Override
  public String toString() {
    return getName();
  }

  @Override
  public int getWeight() {
    return 10;
  }

  protected SimpleTextAttributes getErrorAttributes() {
    return new SimpleTextAttributes(SimpleTextAttributes.STYLE_WAVED, getColor(), JBColor.RED);
  }

  protected SimpleTextAttributes getPlainAttributes() {
    Color color = getColor(); // the most common case is no color, regular attributes, avoid memory allocation in this case
    return color == null ? SimpleTextAttributes.REGULAR_ATTRIBUTES : new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color);
  }

  @Nullable
  protected Object updateElement() {
    return getElement();
  }

  @Override
  protected void update(@NotNull PresentationData presentation) {
    Object newElement = updateElement();
    if (getElement() != newElement) {
      presentation.setChanged(true);
    }
    if (newElement == null) return;

    Color oldColor = myColor;
    String oldName = myName;
    Icon oldIcon = getIcon();
    List<ColoredFragment> oldFragments = new ArrayList<>(presentation.getColoredText());

    myColor = UIUtil.getTreeForeground();

    doUpdate(presentation);

    myName = getName();
    presentation.setPresentableText(myName);

    presentation.setChanged(!Arrays.equals(new Object[]{getIcon(), myName, oldFragments, myColor},
                                           new Object[]{oldIcon, oldName, oldFragments, oldColor}));

    presentation.setForcedTextForeground(myColor);
    presentation.setIcon(getIcon());
  }

  /**
   * @deprecated use {@link #getTemplatePresentation()} to set constant presentation right in node's constructor
   * or update presentation dynamically by defining {@link #update(PresentationData)}
   */
  @Deprecated
  public final void setNodeText(String text, String tooltip, boolean hasError) {
    clearColoredText();
    SimpleTextAttributes attributes = hasError ? getErrorAttributes() : getPlainAttributes();
    getTemplatePresentation().addText(new ColoredFragment(text, tooltip, attributes));
  }

  /**
   * @deprecated use {@link #getTemplatePresentation()} to set constant presentation right in node's constructor
   * or update presentation dynamically by defining {@link #update(PresentationData)}
   */
  @Deprecated
  public final void setPlainText(String aText) {
    clearColoredText();
    getTemplatePresentation().addText(new ColoredFragment(aText, getPlainAttributes()));
  }

  /**
   * @deprecated use {@link #getTemplatePresentation()} to set constant presentation right in node's constructor
   * or update presentation dynamically by defining {@link #update(PresentationData)}
   */
  @Deprecated
  public final void clearColoredText() {
    getTemplatePresentation().clearText();
  }

  /**
   * @deprecated use {@link #getTemplatePresentation()} to set constant presentation right in node's constructor
   * or update presentation dynamically by defining {@link #update(PresentationData)}
   */
  @Deprecated
  public final void addColoredFragment(String aText, SimpleTextAttributes aAttributes) {
    addColoredFragment(aText, null, aAttributes);
  }

  /**
   * @deprecated use {@link #getTemplatePresentation()} to set constant presentation right in node's constructor
   * or update presentation dynamically by defining {@link #update(PresentationData)}
   */
  @Deprecated
  public final void addColoredFragment(String aText, String toolTip, SimpleTextAttributes aAttributes) {
    getTemplatePresentation().addText(new ColoredFragment(aText, toolTip, aAttributes));
  }

  /**
   * Updates properties of the node's presentation.
   * <p>
   *   This method is called as a part of update process. During the update, the template presentation is cloned,
   *   then the new element is computed (by calling {@link #updateElement()} and if it's not {@code null},
   *   then this method is called with the cloned template presentation passed to it.
   * </p>
   * <p>
   *   This method <em>only</em> change the given presentation.
   *   In particular, should not change the template presentation (use the constructor or {@link #createPresentation()} for that),
   *   nor the current presentation (it will be replaced by the given presentation when the update is finished),
   *   nor the object's own fields (name, icon, color) - these can be set in the constructor or <em>before</em> the update.
   *   Altering this object's state in any way in this method can cause race conditions and subtle UI bugs, as it's often called on
   *   the background thread, and therefore the only safe way to deal with update is to publish the new presentation after the
   *   update is finished, and not to change any state during the update.
   * </p>
   * <p>
   *   To set the text, <em>either</em> use {@link PresentationData#addText(ColoredFragment)} / {@link PresentationData#addText(String, SimpleTextAttributes)}
   *   (don't forget to call {@link PresentationData#clearText()} first, as it's initially copied from the template presentation!)
   *   or {@link PresentationData#setPresentableText(String)}. The former takes precedence: when the update is done, the plain text version is
   *   set to the colored text (with coloring removed) if any.
   * </p>
   * <p>
   *   Note that if, at the end of the update, text, some of the text, color, icon properties are {@code null}, then
   *   {@link PresentableNodeDescriptor} falls back to using own properties: {@link #myName}, {@link #getIcon()}, {@link #getColor()},
   *   and uses them to fill in the missing properties of the updated presentation.
   *   This is intended for classes that never bother to override {@link #update(PresentationData)} or this method, but still set some of those
   *   properties.
   * </p>
   */
  protected void doUpdate(@NotNull PresentationData presentation) {
  }

  @Override
  public Object getElement() {
    return this;
  }

  public final SimpleNode getParent() {
    return (SimpleNode)getParentDescriptor();
  }

  public int getIndex(SimpleNode child) {
    final SimpleNode[] kids = getChildren();
    for (int i = 0; i < kids.length; i++) {
      SimpleNode each = kids[i];
      if (each.equals(child)) return i;
    }

    return -1;
  }

  public abstract SimpleNode @NotNull [] getChildren();

  public void handleSelection(SimpleTree tree) {
  }

  public void handleDoubleClickOrEnter(SimpleTree tree, InputEvent inputEvent) {
  }

  @NotNull
  @Override
  public LeafState getLeafState() {
    if (isAlwaysShowPlus()) return LeafState.NEVER;
    if (isAlwaysLeaf()) return LeafState.ALWAYS;
    return LeafState.DEFAULT;
  }

  public boolean isAlwaysShowPlus() {
    return false;
  }

  public boolean isAutoExpandNode() {
    return false;
  }

  public boolean isAlwaysLeaf() {
    return false;
  }

  public boolean shouldHaveSeparator() {
    return false;
  }

  /**
   * @deprecated use {@link #getTemplatePresentation()} to set constant presentation right in node's constructor
   * or update presentation dynamically by defining {@link #update(PresentationData)}
   */
  @Deprecated(forRemoval = true)
  public void setUniformIcon(Icon aIcon) {
    setIcon(aIcon);
  }

  @Override
  public Object @NotNull [] getEqualityObjects() {
    return NONE;
  }

  public int getChildCount() {
    return getChildren().length;
  }

  public SimpleNode getChildAt(final int i) {
    return getChildren()[i];
  }

  @Override
  public final boolean equals(Object o) {
    return ComparableObjectCheck.equals(this, o);
  }

  @Override
  public final int hashCode() {
    return ComparableObjectCheck.hashCode(this, super.hashCode());
  }

}
