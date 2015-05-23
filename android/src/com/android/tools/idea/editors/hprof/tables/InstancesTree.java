/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.editors.hprof.tables;

import com.android.tools.idea.editors.allocations.ColumnTreeBuilder;
import com.android.tools.idea.editors.hprof.descriptors.*;
import com.android.tools.perflib.heap.*;
import com.intellij.debugger.engine.DebugProcessEvents;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.SuspendManagerImpl;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.impl.DebuggerContextImpl;
import com.intellij.debugger.ui.impl.DebuggerTreeRenderer;
import com.intellij.debugger.ui.impl.tree.TreeBuilder;
import com.intellij.debugger.ui.impl.tree.TreeBuilderNode;
import com.intellij.debugger.ui.impl.watch.DebuggerTree;
import com.intellij.debugger.ui.impl.watch.DebuggerTreeNodeImpl;
import com.intellij.debugger.ui.impl.watch.DefaultNodeDescriptor;
import com.intellij.debugger.ui.impl.watch.NodeDescriptorImpl;
import com.intellij.debugger.ui.tree.NodeDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.sun.jdi.request.EventRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.TreePath;
import java.util.*;

public class InstancesTree {
  private static final int NODES_PER_EXPANSION = 100;

  @NotNull private DebuggerTree myDebuggerTree;
  @NotNull private JComponent myColumnTree;
  @NotNull private DebugProcessImpl myDebugProcess;
  @NotNull private volatile SuspendContextImpl myDummySuspendContext;
  @NotNull private Heap myHeap;
  @Nullable private Comparator<DebuggerTreeNodeImpl> myComparator;
  @NotNull private SortOrder mySortOrder = SortOrder.UNSORTED;

  public InstancesTree(@NotNull Project project, @NotNull Heap heap, @NotNull TreeSelectionListener treeSelectionListener) {
    myDebuggerTree = new DebuggerTree(project) {
      @Override
      protected void build(DebuggerContextImpl context) {
        DebuggerTreeNodeImpl root = (DebuggerTreeNodeImpl)getModel().getRoot();
        Instance instance = ((InstanceFieldDescriptorImpl)root.getDescriptor()).getInstance();
        addChildren(root, null, instance);
      }
    };
    myHeap = heap;
    myDebugProcess = new DebugProcessEvents(project);
    final SuspendManagerImpl suspendManager = new SuspendManagerImpl(myDebugProcess);
    myDebugProcess.getManagerThread().invokeAndWait(new DebuggerCommandImpl() {
      @Override
      protected void action() throws Exception {
        myDummySuspendContext = suspendManager.pushSuspendContext(EventRequest.SUSPEND_NONE, 1);
      }
    });

    final TreeBuilder model = new TreeBuilder(myDebuggerTree) {
      @Override
      public void buildChildren(TreeBuilderNode node) {
        final DebuggerTreeNodeImpl debuggerTreeNode = (DebuggerTreeNodeImpl)node;
        NodeDescriptor descriptor = debuggerTreeNode.getDescriptor();
        if (descriptor instanceof DefaultNodeDescriptor) {
          return;
        }
        else if (descriptor instanceof ContainerDescriptorImpl) {
          addContainerChildren(debuggerTreeNode, 0);
        }
        else {
          InstanceFieldDescriptorImpl instanceDescriptor = (InstanceFieldDescriptorImpl)descriptor;
          addChildren(debuggerTreeNode, instanceDescriptor.getHprofField(), instanceDescriptor.getInstance());
        }

        sortTree(debuggerTreeNode);
        myDebuggerTree.treeDidChange();
      }

      @Override
      public boolean isExpandable(TreeBuilderNode builderNode) {
        return ((DebuggerTreeNodeImpl)builderNode).getDescriptor().isExpandable();
      }
    };
    model.setRoot(myDebuggerTree.getNodeFactory().getDefaultNode());
    model.addTreeModelListener(new TreeModelListener() {
      @Override
      public void treeNodesChanged(TreeModelEvent event) {
        myDebuggerTree.hideTooltip();
      }

      @Override
      public void treeNodesInserted(TreeModelEvent event) {
        myDebuggerTree.hideTooltip();
      }

      @Override
      public void treeNodesRemoved(TreeModelEvent event) {
        myDebuggerTree.hideTooltip();
      }

      @Override
      public void treeStructureChanged(TreeModelEvent event) {
        myDebuggerTree.hideTooltip();
      }
    });
    myDebuggerTree.setModel(model);
    myDebuggerTree.setRootVisible(false);
    myDebuggerTree.addTreeSelectionListener(treeSelectionListener);

    // Add a listener specifically for detecting if the user decided to show more nodes.
    myDebuggerTree.addTreeSelectionListener(new TreeSelectionListener() {
      @Override
      public void valueChanged(TreeSelectionEvent e) {
        TreePath path = e.getPath();
        if (path == null || path.getPathCount() < 2 || !e.isAddedPath()) {
          return;
        }

        DebuggerTreeNodeImpl node = (DebuggerTreeNodeImpl)path.getLastPathComponent();
        if (node.getDescriptor() instanceof ExpansionDescriptorImpl) {
          ExpansionDescriptorImpl expansionDescriptor = (ExpansionDescriptorImpl)node.getDescriptor();
          DebuggerTreeNodeImpl parentNode = node.getParent();
          myDebuggerTree.getMutableModel().removeNodeFromParent(node);

          if (parentNode.getDescriptor() instanceof ContainerDescriptorImpl) {
            addContainerChildren(parentNode, expansionDescriptor.getStartIndex());
          }
          else if (parentNode.getDescriptor() instanceof InstanceFieldDescriptorImpl) {
            InstanceFieldDescriptorImpl instanceFieldDescriptor = (InstanceFieldDescriptorImpl)parentNode.getDescriptor();
            addChildren(parentNode, instanceFieldDescriptor.getHprofField(), instanceFieldDescriptor.getInstance(),
                        expansionDescriptor.getStartIndex());
          }

          sortTree(parentNode);
          myDebuggerTree.getMutableModel().nodeStructureChanged(parentNode);

          if (myComparator != null) {
            myDebuggerTree.scrollPathToVisible(new TreePath(((DebuggerTreeNodeImpl)parentNode.getLastChild()).getPath()));
          }
        }
      }
    });

    ColumnTreeBuilder builder = new ColumnTreeBuilder(myDebuggerTree).addColumn(
        new ColumnTreeBuilder.ColumnBuilder()
          .setName("Instance")
          .setPreferredWidth(600)
          .setComparator(new Comparator<DebuggerTreeNodeImpl>() {
            @Override
            public int compare(@NotNull DebuggerTreeNodeImpl a, @NotNull DebuggerTreeNodeImpl b) {
              return getDefaultOrdering(a, b);
            }
          })
          .setRenderer((DebuggerTreeRenderer)myDebuggerTree.getCellRenderer())
        )
      .addColumn(
        new ColumnTreeBuilder.ColumnBuilder()
          .setName("Shallow Size")
          .setPreferredWidth(80)
          .setComparator(new Comparator<DebuggerTreeNodeImpl>() {
            @Override
            public int compare(@NotNull DebuggerTreeNodeImpl a, @NotNull DebuggerTreeNodeImpl b) {
              int sizeA = 0;
              int sizeB = 0;
              if (a.getDescriptor() instanceof InstanceFieldDescriptorImpl) {
                Instance instanceA = (Instance)((InstanceFieldDescriptorImpl)a.getDescriptor()).getValueData();
                if (instanceA != null) {
                  sizeA = instanceA.getSize();
                }
              }
              if (b.getDescriptor() instanceof InstanceFieldDescriptorImpl) {
                Instance instanceB = (Instance)((InstanceFieldDescriptorImpl)b.getDescriptor()).getValueData();
                if (instanceB != null) {
                  sizeB = instanceB.getSize();
                }
              }
              if (sizeA != sizeB) {
                return sizeA - sizeB;
              }
              else {
                return getDefaultOrdering(a, b);
              }
            }
          })
          .setRenderer(new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree,
                                               Object value,
                                               boolean selected,
                                               boolean expanded,
                                               boolean leaf,
                                               int row,
                                               boolean hasFocus) {
              NodeDescriptorImpl nodeDescriptor = (NodeDescriptorImpl)((TreeBuilderNode)value).getUserObject();
              if (nodeDescriptor instanceof InstanceFieldDescriptorImpl) {
                InstanceFieldDescriptorImpl descriptor = (InstanceFieldDescriptorImpl)nodeDescriptor;
                assert !descriptor.isPrimitive();
                Instance instance = (Instance)descriptor.getValueData();
                if (instance != null) {
                  append(String.valueOf(instance.getSize()), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                }
              }
              setTextAlign(SwingConstants.RIGHT);
            }
          })
        )
      .addColumn(
        new ColumnTreeBuilder.ColumnBuilder()
          .setName("Dominating Size").setPreferredWidth(80)
          .setComparator(new Comparator<DebuggerTreeNodeImpl>() {
            @Override
            public int compare(@NotNull DebuggerTreeNodeImpl a, @NotNull DebuggerTreeNodeImpl b) {
             long sizeA = 0;
             long sizeB = 0;
             if (a.getDescriptor() instanceof InstanceFieldDescriptorImpl) {
               Instance instanceA = (Instance)((InstanceFieldDescriptorImpl)a.getDescriptor()).getValueData();
               if (instanceA != null && instanceA.getDistanceToGcRoot() != Integer.MAX_VALUE) {
                 sizeA = instanceA.getTotalRetainedSize();
               }
             }
             if (b.getDescriptor() instanceof InstanceFieldDescriptorImpl) {
               Instance instanceB = (Instance)((InstanceFieldDescriptorImpl)b.getDescriptor()).getValueData();
               if (instanceB != null && instanceB.getDistanceToGcRoot() != Integer.MAX_VALUE) {
                 sizeB = instanceB.getTotalRetainedSize();
               }
             }
             if (sizeA != sizeB) {
               return (int)(sizeA - sizeB);
             }
             else {
               return getDefaultOrdering(a, b);
             }
            }
          }).setRenderer(new ColoredTreeCellRenderer() {
          @Override
          public void customizeCellRenderer(@NotNull JTree tree,
                                            Object value,
                                            boolean selected,
                                            boolean expanded,
                                            boolean leaf,
                                            int row,
                                            boolean hasFocus) {
            NodeDescriptorImpl nodeDescriptor = (NodeDescriptorImpl)((TreeBuilderNode)value).getUserObject();
            if (nodeDescriptor instanceof InstanceFieldDescriptorImpl) {
              InstanceFieldDescriptorImpl descriptor = (InstanceFieldDescriptorImpl)nodeDescriptor;
              assert !descriptor.isPrimitive();
              Instance instance = (Instance)descriptor.getValueData();
              if (instance != null && instance.getDistanceToGcRoot() != Integer.MAX_VALUE) {
                append(String.valueOf(instance.getTotalRetainedSize()), SimpleTextAttributes.REGULAR_ATTRIBUTES);
              }
            }
            setTextAlign(SwingConstants.RIGHT);
          }
        }));

    //noinspection NullableProblems
    builder.setTreeSorter(new ColumnTreeBuilder.TreeSorter<DebuggerTreeNodeImpl>() {
      @Override
      public void sort(@NotNull Comparator<DebuggerTreeNodeImpl> comparator, @NotNull SortOrder sortOrder) {
        if (myComparator != comparator && mySortOrder != sortOrder) {
          myComparator = comparator;
          mySortOrder = sortOrder;
          TreeBuilder mutableModel = myDebuggerTree.getMutableModel();
          DebuggerTreeNodeImpl root = (DebuggerTreeNodeImpl)mutableModel.getRoot();

          sortTree(root);
          mutableModel.nodeStructureChanged(root);
        }
      }
    });

    myColumnTree = builder.build();
  }

  @NotNull
  public JComponent getComponent() {
    return myColumnTree;
  }

  private void sortTree(@NotNull DebuggerTreeNodeImpl node) {
    if (myComparator == null) {
      return;
    }

    // We don't want to accidentally build children, so we have to get the raw children instead.
    Enumeration e = node.rawChildren();
    if (e.hasMoreElements()) {
      //noinspection unchecked
      ArrayList<DebuggerTreeNodeImpl> builtChildren = Collections.list(e);

      // First check if there's an expansion node. Remove if there is, and add it back at the end.
      DebuggerTreeNodeImpl expansionNode = builtChildren.get(builtChildren.size() - 1);
      if (expansionNode.getDescriptor() instanceof ExpansionDescriptorImpl) {
        builtChildren.remove(builtChildren.size() - 1);
      }
      else {
        expansionNode = null;
      }

      Collections.sort(builtChildren, myComparator);
      node.removeAllChildren(); // Remove children after sorting, since the sort may depend on the parent information.
      for (DebuggerTreeNodeImpl childNode : builtChildren) {
        node.add(childNode);
        sortTree(childNode);
      }

      if (expansionNode != null) {
        node.add(expansionNode);
      }
    }
  }

  private int getDefaultOrdering(@NotNull DebuggerTreeNodeImpl a, @NotNull DebuggerTreeNodeImpl b) {
    NodeDescriptorImpl parentDescriptor = a.getParent().getDescriptor();
    if (parentDescriptor instanceof InstanceFieldDescriptorImpl) {
      Instance parentInstance = ((InstanceFieldDescriptorImpl)parentDescriptor).getInstance();
      if (parentInstance instanceof ArrayInstance) {
        return getMemoryOrderingSortResult(a, b);
      }
    }
    else if (parentDescriptor instanceof ContainerDescriptorImpl) {
      return getMemoryOrderingSortResult(a, b);
    }
    return a.getDescriptor().getLabel().compareTo(b.getDescriptor().getLabel());
  }

  private int getMemoryOrderingSortResult(@NotNull DebuggerTreeNodeImpl a, @NotNull DebuggerTreeNodeImpl b) {
    return (((HprofFieldDescriptorImpl)a.getDescriptor()).getMemoryOrdering() -
            ((HprofFieldDescriptorImpl)b.getDescriptor()).getMemoryOrdering()) ^
           (mySortOrder == SortOrder.ASCENDING ? 1 : -1);
  }

  public void setClassObj(@NotNull Heap heap, @Nullable ClassObj classObj) {
    if (myDebuggerTree.getMutableModel().getRoot() == null) {
      return;
    }

    if (myHeap == heap && ((DebuggerTreeNodeImpl)myDebuggerTree.getModel().getRoot()).getDescriptor() instanceof ContainerDescriptorImpl) {
      if (getClassObj() == classObj) {
        return;
      }
    }

    myHeap = heap;
    DebuggerTreeNodeImpl newRoot;

    if (classObj != null) {
      ContainerDescriptorImpl containerDescriptor = new ContainerDescriptorImpl(classObj, heap.getId());
      newRoot = DebuggerTreeNodeImpl.createNodeNoUpdate(myDebuggerTree, containerDescriptor);
    }
    else {
      newRoot = myDebuggerTree.getNodeFactory().getDefaultNode();
    }
    myDebuggerTree.getMutableModel().setRoot(newRoot);
    myDebuggerTree.treeChanged();
    myDebuggerTree.scrollRowToVisible(0);
  }

  @Nullable
  public ClassObj getClassObj() {
    DebuggerTreeNodeImpl node = (DebuggerTreeNodeImpl)myDebuggerTree.getMutableModel().getRoot();
    if (node.getDescriptor() instanceof DefaultNodeDescriptor) {
      return null;
    }
    else {
      ContainerDescriptorImpl containerDescriptor = (ContainerDescriptorImpl)node.getDescriptor();
      return containerDescriptor.getClassObj();
    }
  }

  private void addContainerChildren(@NotNull DebuggerTreeNodeImpl node, int startIndex) {
    ContainerDescriptorImpl containerDescriptor = (ContainerDescriptorImpl)node.getDescriptor();
    List<Instance> instances = containerDescriptor.getInstances();
    List<HprofFieldDescriptorImpl> descriptors = new ArrayList<HprofFieldDescriptorImpl>(NODES_PER_EXPANSION);
    int currentIndex = startIndex;
    int limit = currentIndex + NODES_PER_EXPANSION;
    for (int loopCounter = currentIndex; loopCounter < instances.size() && currentIndex < limit; ++loopCounter) {
      Instance instance = instances.get(loopCounter);
      if (myHeap.getInstance(instance.getId()) != null) {
        descriptors.add(new InstanceFieldDescriptorImpl(
          myDebuggerTree.getProject(),
          new Field(Type.OBJECT, String.format("0x%x (%d)", instance.getUniqueId(), currentIndex)),
          instance,
          currentIndex));
        ++currentIndex;
      }
    }
    HprofFieldDescriptorImpl.batchUpdateRepresentation(descriptors, myDebugProcess.getManagerThread(), myDummySuspendContext);
    for (HprofFieldDescriptorImpl descriptor : descriptors) {
      node.add(DebuggerTreeNodeImpl.createNodeNoUpdate(myDebuggerTree, descriptor));
    }
    if (currentIndex == limit) {
      node.add(DebuggerTreeNodeImpl.createNodeNoUpdate(myDebuggerTree, new ExpansionDescriptorImpl("instances", limit, instances.size())));
    }
  }

  private void addChildren(@NotNull DebuggerTreeNodeImpl node, @Nullable Field field, @Nullable Instance instance) {
    addChildren(node, field, instance, 0);
  }

  private void addChildren(@NotNull DebuggerTreeNodeImpl node, @Nullable Field field, @Nullable Instance instance, int arrayStartIndex) {
    if (instance == null) {
      return;
    }

    // These local variables are used for adding an expansion node for array tree node expansion.
    int currentArrayIndex = arrayStartIndex;
    int limit = currentArrayIndex + NODES_PER_EXPANSION;
    int arrayLength = 0;

    List<HprofFieldDescriptorImpl> descriptors;
    if (instance instanceof ClassInstance) {
      ClassInstance classInstance = (ClassInstance)instance;
      descriptors = new ArrayList<HprofFieldDescriptorImpl>(classInstance.getValues().size());
      int i = 0;
      for (Map.Entry<Field, Object> entry : classInstance.getValues().entrySet()) {
        if (entry.getKey().getType() == Type.OBJECT) {
          descriptors.add(new InstanceFieldDescriptorImpl(myDebuggerTree.getProject(), entry.getKey(), (Instance)entry.getValue(), i));
        }
        else {
          descriptors.add(new PrimitiveFieldDescriptorImpl(myDebuggerTree.getProject(), entry.getKey(), entry.getValue(), i));
        }
        ++i;
      }
    }
    else if (instance instanceof ArrayInstance) {
      assert (field != null);
      ArrayInstance arrayInstance = (ArrayInstance)instance;
      Object[] values = arrayInstance.getValues();
      descriptors = new ArrayList<HprofFieldDescriptorImpl>(values.length);
      arrayLength = values.length;

      if (arrayInstance.getArrayType() == Type.OBJECT) {
        while (currentArrayIndex < arrayLength && currentArrayIndex < limit) {
          descriptors.add(
            new InstanceFieldDescriptorImpl(
              myDebuggerTree.getProject(),
              new Field(arrayInstance.getArrayType(), String.valueOf(currentArrayIndex)),
              (Instance)values[currentArrayIndex], currentArrayIndex));
          ++currentArrayIndex;
        }
      }
      else {
        while (currentArrayIndex < arrayLength && currentArrayIndex < limit) {
          descriptors.add(
            new PrimitiveFieldDescriptorImpl(myDebuggerTree.getProject(),
              new Field(arrayInstance.getArrayType(), String.valueOf(currentArrayIndex)), values[currentArrayIndex], currentArrayIndex));
          ++currentArrayIndex;
        }
      }
    }
    else {
      throw new RuntimeException("Unimplemented Instance type in addChildren.");
    }

    HprofFieldDescriptorImpl.batchUpdateRepresentation(descriptors, myDebugProcess.getManagerThread(), myDummySuspendContext);
    for (HprofFieldDescriptorImpl descriptor : descriptors) {
      node.add(DebuggerTreeNodeImpl.createNodeNoUpdate(myDebuggerTree, descriptor));
    }

    if (currentArrayIndex == limit) {
      node.add(DebuggerTreeNodeImpl.createNodeNoUpdate(myDebuggerTree, new ExpansionDescriptorImpl("array elements", limit, arrayLength)));
    }
  }
}
