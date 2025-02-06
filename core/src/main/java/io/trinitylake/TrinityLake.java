/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trinitylake;

import com.google.common.collect.ImmutableMap;
import io.trinitylake.exception.CommitFailureException;
import io.trinitylake.exception.ObjectAlreadyExistsException;
import io.trinitylake.exception.ObjectNotFoundException;
import io.trinitylake.models.LakehouseDef;
import io.trinitylake.models.NamespaceDef;
import io.trinitylake.models.TableDef;
import io.trinitylake.storage.AtomicOutputStream;
import io.trinitylake.storage.LakehouseStorage;
import io.trinitylake.tree.BasicTreeNode;
import io.trinitylake.tree.NodeKeyTableRow;
import io.trinitylake.tree.TreeNode;
import io.trinitylake.tree.TreeOperations;
import io.trinitylake.util.ValidationUtil;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class TrinityLake {

  public static void createLakehouse(LakehouseStorage storage, LakehouseDef lakehouseDef) {
    String lakehouseDefFilePath = ObjectLocations.newLakehouseDefFilePath();
    ObjectDefinitions.writeLakehouseDef(storage, lakehouseDefFilePath, lakehouseDef);

    BasicTreeNode root = new BasicTreeNode();
    root.set(ObjectKeys.LAKEHOUSE_DEFINITION, lakehouseDefFilePath);
    root.set(ObjectKeys.NUMBER_OF_KEYS, Long.toString(0));
    String rootNodeFilePath = ObjectLocations.rootNodeFilePath(0);
    AtomicOutputStream stream = storage.startWrite(rootNodeFilePath);
    TreeOperations.writeNodeFile(stream, root);
  }

  public static RunningTransaction beginTransaction(LakehouseStorage storage) {
    return beginTransaction(storage, ImmutableMap.of());
  }

  public static RunningTransaction beginTransaction(
      LakehouseStorage storage, Map<String, String> options) {
    TreeNode current = TreeOperations.findLatestRoot(storage);
    TransactionOptions transactionOptions = new TransactionOptions(options);
    return ImmutableRunningTransaction.builder()
        .beganAtMillis(System.currentTimeMillis())
        .transactionId(UUID.randomUUID().toString())
        .beginningRoot(current)
        .runningRoot(current)
        .isolationLevel(transactionOptions.isolationLevel())
        .build();
  }

  public static CommittedTransaction commitTransaction(
      LakehouseStorage storage, RunningTransaction transaction) throws CommitFailureException {
    ValidationUtil.checkArgument(
        TreeOperations.hasVersion(transaction.runningRoot()), "There is no change to be committed");
    long beginningRootVersion = TreeOperations.findVersionFromRootNode(transaction.beginningRoot());
    String nextVersionFilePath = ObjectLocations.rootNodeFilePath(beginningRootVersion + 1);
    AtomicOutputStream stream = storage.startWrite(nextVersionFilePath);
    TreeOperations.writeNodeFile(stream, transaction.runningRoot());
    return ImmutableCommittedTransaction.builder()
        .committedRoot(transaction.runningRoot())
        .transactionId(transaction.transactionId())
        .build();
  }

  public static List<String> showNamespaces(
      LakehouseStorage storage, RunningTransaction transaction) {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    return transaction.runningRoot().nodeKeyTable().stream()
        .map(NodeKeyTableRow::key)
        .filter(key -> ObjectKeys.isNamespaceKey(key, lakehouseDef))
        .map(key -> ObjectKeys.namespaceNameFromKey(key, lakehouseDef))
        .collect(Collectors.toList());
  }

  public static boolean namespaceExists(
      LakehouseStorage storage, RunningTransaction transaction, String namespaceName) {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }
    return transaction.runningRoot().contains(namespaceKey);
  }

  public static NamespaceDef describeNamespace(
      LakehouseStorage storage, RunningTransaction transaction, String namespaceName)
      throws ObjectNotFoundException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }
    String namespaceDefFilePath = transaction.runningRoot().get(namespaceKey);
    return ObjectDefinitions.readNamespaceDef(storage, namespaceDefFilePath);
  }

  public static RunningTransaction createNamespace(
      LakehouseStorage storage,
      RunningTransaction transaction,
      String namespaceName,
      NamespaceDef namespaceDef)
      throws ObjectAlreadyExistsException, CommitFailureException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectAlreadyExistsException("Namespace %s already exists", namespaceName);
    }

    String namespaceDefFilePath = ObjectLocations.newNamespaceDefFilePath(namespaceName);
    ObjectDefinitions.writeNamespaceDef(storage, namespaceDefFilePath, namespaceName, namespaceDef);
    TreeNode newRoot = TreeOperations.clone(transaction.runningRoot());
    newRoot.set(namespaceKey, namespaceDefFilePath);
    return ImmutableRunningTransaction.builder().from(transaction).runningRoot(newRoot).build();
  }

  public static RunningTransaction alterNamespace(
      LakehouseStorage storage,
      RunningTransaction transaction,
      String namespaceName,
      NamespaceDef namespaceDef)
      throws ObjectNotFoundException, CommitFailureException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }

    String namespaceDefFilePath = ObjectLocations.newNamespaceDefFilePath(namespaceName);
    ObjectDefinitions.writeNamespaceDef(storage, namespaceDefFilePath, namespaceName, namespaceDef);
    TreeNode newRoot = TreeOperations.clone(transaction.runningRoot());
    newRoot.set(namespaceKey, namespaceDefFilePath);
    return ImmutableRunningTransaction.builder().from(transaction).runningRoot(newRoot).build();
  }

  public static RunningTransaction dropNamespace(
      LakehouseStorage storage, RunningTransaction transaction, String namespaceName)
      throws ObjectNotFoundException, CommitFailureException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }

    TreeNode newRoot = TreeOperations.clone(transaction.runningRoot());
    newRoot.remove(namespaceKey);
    return ImmutableRunningTransaction.builder().from(transaction).runningRoot(newRoot).build();
  }

  public static List<String> showTables(
      LakehouseStorage storage, RunningTransaction transaction, String namespaceName)
      throws ObjectNotFoundException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    return transaction.runningRoot().nodeKeyTable().stream()
        .map(NodeKeyTableRow::key)
        .filter(key -> ObjectKeys.isTableKey(key, lakehouseDef))
        .map(key -> ObjectKeys.tableNameFromKey(key, lakehouseDef))
        .collect(Collectors.toList());
  }

  public static boolean tableExists(
      LakehouseStorage storage,
      RunningTransaction transaction,
      String namespaceName,
      String tableName) {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }
    String tableKey = ObjectKeys.tableKey(namespaceName, tableName, lakehouseDef);
    if (!transaction.runningRoot().contains(tableKey)) {
      throw new ObjectNotFoundException(
          "Namespace %s table %s does not exist", namespaceName, tableName);
    }
    return transaction.runningRoot().contains(tableKey);
  }

  public static TableDef describeTable(
      LakehouseStorage storage,
      RunningTransaction transaction,
      String namespaceName,
      String tableName)
      throws ObjectNotFoundException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }
    String tableKey = ObjectKeys.tableKey(namespaceName, tableName, lakehouseDef);
    if (!transaction.runningRoot().contains(tableKey)) {
      throw new ObjectNotFoundException(
          "Namespace %s table %s does not exist", namespaceName, tableName);
    }
    String tableDefFilePath = transaction.runningRoot().get(tableKey);
    return ObjectDefinitions.readTableDef(storage, tableDefFilePath);
  }

  public static RunningTransaction createTable(
      LakehouseStorage storage,
      RunningTransaction transaction,
      String namespaceName,
      String tableName,
      TableDef tableDef)
      throws ObjectAlreadyExistsException, CommitFailureException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }
    String tableKey = ObjectKeys.tableKey(namespaceName, tableName, lakehouseDef);
    if (transaction.runningRoot().contains(tableKey)) {
      throw new ObjectAlreadyExistsException(
          "Namespace %s table %s already exists", namespaceName, tableName);
    }

    String tableDefFilePath = ObjectLocations.newTableDefFilePath(namespaceName, tableName);
    ObjectDefinitions.writeTableDef(storage, tableDefFilePath, namespaceName, tableName, tableDef);
    TreeNode newRoot = TreeOperations.clone(transaction.runningRoot());
    newRoot.set(tableKey, tableDefFilePath);
    return ImmutableRunningTransaction.builder().from(transaction).runningRoot(newRoot).build();
  }

  public static RunningTransaction alterTable(
      LakehouseStorage storage,
      RunningTransaction transaction,
      String namespaceName,
      String tableName,
      TableDef tableDef)
      throws ObjectNotFoundException, CommitFailureException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }
    String tableKey = ObjectKeys.tableKey(namespaceName, tableName, lakehouseDef);
    if (!transaction.runningRoot().contains(tableKey)) {
      throw new ObjectNotFoundException(
          "Namespace %s table %s does not exists", namespaceName, tableName);
    }

    String tableDefFilePath = ObjectLocations.newTableDefFilePath(namespaceName, tableName);
    ObjectDefinitions.writeTableDef(storage, tableDefFilePath, namespaceName, tableName, tableDef);
    TreeNode newRoot = TreeOperations.clone(transaction.runningRoot());
    newRoot.set(tableKey, tableDefFilePath);
    return ImmutableRunningTransaction.builder().from(transaction).runningRoot(newRoot).build();
  }

  public static RunningTransaction dropTable(
      LakehouseStorage storage,
      RunningTransaction transaction,
      String namespaceName,
      String tableName)
      throws ObjectNotFoundException, CommitFailureException {
    LakehouseDef lakehouseDef = TreeOperations.findLakehouseDef(storage, transaction.runningRoot());
    String namespaceKey = ObjectKeys.namespaceKey(namespaceName, lakehouseDef);
    if (!transaction.runningRoot().contains(namespaceKey)) {
      throw new ObjectNotFoundException("Namespace %s does not exist", namespaceName);
    }
    String tableKey = ObjectKeys.tableKey(namespaceName, tableName, lakehouseDef);
    if (!transaction.runningRoot().contains(tableKey)) {
      throw new ObjectNotFoundException(
          "Namespace %s table %s does not exists", namespaceName, tableName);
    }

    TreeNode newRoot = TreeOperations.clone(transaction.runningRoot());
    newRoot.remove(tableKey);
    return ImmutableRunningTransaction.builder().from(transaction).runningRoot(newRoot).build();
  }
}
