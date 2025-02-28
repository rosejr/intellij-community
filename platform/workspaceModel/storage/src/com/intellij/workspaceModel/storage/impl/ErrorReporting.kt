// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.storage.impl

import com.esotericsoftware.kryo.kryo5.io.Output
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.io.Compressor
import com.intellij.workspaceModel.storage.*
import com.intellij.workspaceModel.storage.impl.url.VirtualFileUrlManagerImpl
import org.jetbrains.annotations.ApiStatus
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.io.path.writeText

@ApiStatus.Internal
fun reportErrorAndAttachStorage(message: String, storage: EntityStorage) {
  reportConsistencyIssue(message, IllegalStateException(), null, null, null, storage)
}

internal fun serializeContent(path: Path, howToSerialize: (EntityStorageSerializerImpl, OutputStream) -> Unit) {
  val serializer = EntityStorageSerializerImpl(SimpleEntityTypesResolver, VirtualFileUrlManagerImpl())
  path.toFile().outputStream().use { howToSerialize(serializer, it) }
}

internal fun serializeEntityStorage(path: Path, storage: EntityStorage) {
  serializeContent(path) { serializer, stream ->
    serializer.serializeCache(stream, storage.toSnapshot())
  }
}

internal fun getStoreDumpDirectory(): Path {
  val property = System.getProperty("ide.new.project.model.store.dump.directory")
  return if (property == null) {
    val pathPrefix = "storeDump-" + formatTime(System.currentTimeMillis())
    val workspaceModelDumps = Paths.get(PathManager.getLogPath(), "workspaceModel")
    cleanOldFiles(workspaceModelDumps.toFile())
    val currentDumpDir = workspaceModelDumps.resolve(pathPrefix)
    FileUtil.createDirectory(currentDumpDir.toFile())
    return currentDumpDir
  }
  else Paths.get(property)
}

internal fun executingOnTC() = System.getProperty("ide.new.project.model.store.dump.directory") != null

private fun cleanOldFiles(parentDir: File) {
  val children = parentDir.listFiles() ?: return
  Arrays.sort(children)
  for (i in children.indices) {
    val child = children[i]
    // Store latest 30 items in the folder and not older than one week
    if (i < children.size - 30 || ageInDays(child) > 7) FileUtil.delete(child)
  }
}

private fun formatTime(timeMs: Long) = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date(timeMs))

private fun ageInDays(file: File) = TimeUnit.DAYS.convert(System.currentTimeMillis() - file.lastModified(), TimeUnit.MILLISECONDS)

internal fun EntityStorage.serializeTo(stream: OutputStream) {
  val serializer = EntityStorageSerializerImpl(SimpleEntityTypesResolver, VirtualFileUrlManagerImpl())
  serializer.serializeCache(stream, this.toSnapshot())
}

internal fun MutableEntityStorageImpl.serializeDiff(stream: OutputStream) {
  val serializer = EntityStorageSerializerImpl(SimpleEntityTypesResolver, VirtualFileUrlManagerImpl())
  serializeDiff(serializer, stream)
}

private fun MutableEntityStorageImpl.serializeDiff(serializer: EntityStorageSerializerImpl, stream: OutputStream) {
  serializer.serializeDiffLog(stream, this.changeLog.changeLog.anonymize())
}

internal fun EntityStorage.anonymize(sourceFilter: ((EntitySource) -> Boolean)?): EntityStorage {
  if (!isWrapped()) return this
  val builder = MutableEntityStorage.from(this)
  builder.entitiesBySource { true }.flatMap { it.value.flatMap { it.value } }.forEach { entity ->
    builder.modifyEntity(WorkspaceEntity.Builder::class.java, entity) {
      this.entitySource = entity.entitySource.anonymize(sourceFilter)
    }
  }
  return builder.toSnapshot()
}

internal fun ChangeLog.anonymize(): ChangeLog {
  if (!isWrapped()) return this
  val result = HashMap(this)
  result.replaceAll { key, value ->
    when (value) {
      is ChangeEntry.AddEntity -> {
        val newEntityData = value.entityData.clone()
        newEntityData.entitySource = newEntityData.entitySource.anonymize(null)
        ChangeEntry.AddEntity(newEntityData, value.clazz)
      }
      is ChangeEntry.ChangeEntitySource -> {
        val newEntityData = value.newData.clone()
        newEntityData.entitySource = newEntityData.entitySource.anonymize(null)
        ChangeEntry.ChangeEntitySource(value.originalSource.anonymize(null), newEntityData)
      }
      is ChangeEntry.RemoveEntity -> value
      is ChangeEntry.ReplaceAndChangeSource -> {
        val newEntityData = value.sourceChange.newData.clone()
        newEntityData.entitySource = newEntityData.entitySource.anonymize(null)
        val sourceChange = ChangeEntry.ChangeEntitySource(value.sourceChange.originalSource.anonymize(null), newEntityData)

        @Suppress("UNCHECKED_CAST")
        val changedData = value.dataChange.newData.clone() as WorkspaceEntityData<WorkspaceEntity>
        changedData.entitySource = changedData.entitySource.anonymize(null)
        @Suppress("UNCHECKED_CAST")
        val changedOldData = value.dataChange.oldData.clone() as WorkspaceEntityData<WorkspaceEntity>
        changedOldData.entitySource = changedOldData.entitySource.anonymize(null)
        val dataChange = ChangeEntry.ReplaceEntity(changedOldData,
          value.dataChange.oldParents,
          changedData,
          value.dataChange.newChildren,
          value.dataChange.removedChildren,
          value.dataChange.modifiedParents)
        ChangeEntry.ReplaceAndChangeSource(dataChange, sourceChange)
      }
      is ChangeEntry.ReplaceEntity -> {
        @Suppress("UNCHECKED_CAST")
        val newEntityData = value.newData.clone() as WorkspaceEntityData<WorkspaceEntity>
        newEntityData.entitySource = newEntityData.entitySource.anonymize(null)
        @Suppress("UNCHECKED_CAST")
        val oldEntityData = value.oldData.clone() as WorkspaceEntityData<WorkspaceEntity>
        oldEntityData.entitySource = oldEntityData.entitySource.anonymize(null)
        ChangeEntry.ReplaceEntity(oldEntityData, value.oldParents, newEntityData, value.newChildren, value.removedChildren, value.modifiedParents)
      }
    }
  }
  return result
}

internal fun EntitySource.anonymize(sourceFilter: ((EntitySource) -> Boolean)?): EntitySource {
  return if (sourceFilter != null) {
    if (sourceFilter(this)) {
      MatchedEntitySource(this.toString())
    }
    else {
      UnmatchedEntitySource(this.toString())
    }
  }
  else {
    AnonymizedEntitySource(this.toString())
  }
}

data class AnonymizedEntitySource(val originalSourceDump: String) : EntitySource
data class MatchedEntitySource(val originalSourceDump: String) : EntitySource
data class UnmatchedEntitySource(val originalSourceDump: String) : EntitySource

private fun serializeContentToFolder(contentFolder: Path,
                                     left: EntityStorage?,
                                     right: EntityStorage?,
                                     resulting: EntityStorage,
                                     sourceFilter: ((EntitySource) -> Boolean)?): File? {
  if (right is MutableEntityStorage) {
    serializeContent(contentFolder.resolve("Right_Diff_Log")) { serializer, stream ->
      right as MutableEntityStorageImpl
      right.serializeDiff(serializer, stream)
    }
  }

  left?.anonymize(sourceFilter)?.let { serializeEntityStorage(contentFolder.resolve("Left_Store"), it) }
  right?.anonymize(sourceFilter)?.let { serializeEntityStorage(contentFolder.resolve("Right_Store"), it) }
  serializeEntityStorage(contentFolder.resolve("Res_Store"), resulting.anonymize(sourceFilter))
  serializeContent(contentFolder.resolve("ClassToIntConverter")) { serializer, stream -> serializer.serializeClassToIntConverter(stream) }

  val operationName = if (sourceFilter == null) "Add_Diff" else "Replace_By_Source"
  val operationFile = contentFolder.resolve(operationName)
  operationFile.createFile()
  if (!isWrapped()) {
    val entitySourceFilter = if (sourceFilter != null) {
      val allEntitySources = (left as? AbstractEntityStorage)?.indexes?.entitySourceIndex?.entries()?.toHashSet() ?: hashSetOf()
      allEntitySources.addAll((right as? AbstractEntityStorage)?.indexes?.entitySourceIndex?.entries() ?: emptySet())
      allEntitySources.sortedBy { it.toString() }.fold("") { acc, source -> acc + if (sourceFilter(source)) "1" else "0" }
    }
    else ""
    operationFile.writeText(entitySourceFilter)
  }

  if (isWrapped()) contentFolder.resolve("Report_Wrapped").createFile()

  return if (!executingOnTC()) {
    val zipFile = contentFolder.parent.resolve(contentFolder.name + ".zip").toFile()
    Compressor.Zip(zipFile).use { it.addDirectory(contentFolder.toFile()) }
    FileUtil.delete(contentFolder)
    zipFile
  }
  else null
}

private fun EntityStorageSerializerImpl.serializeDiffLog(stream: OutputStream, log: ChangeLog) {
  val output = Output(stream, KRYO_BUFFER_SIZE)
  try {
    val (kryo, _) = createKryo()

    // Save version
    output.writeString(serializerDataFormatVersion)
    saveContributedVersions(kryo, output)

    val entityDataSequence = log.values.mapNotNull {
      when (it) {
        is ChangeEntry.AddEntity -> it.entityData
        is ChangeEntry.RemoveEntity -> null
        is ChangeEntry.ReplaceEntity -> it.newData
        is ChangeEntry.ChangeEntitySource -> it.newData
        is ChangeEntry.ReplaceAndChangeSource -> it.dataChange.newData
      }
    }.asSequence()

    collectAndRegisterClasses(kryo, output, entityDataSequence)

    kryo.writeClassAndObject(output, log)
  }
  finally {
    flush(output)
  }
}

private fun EntityStorageSerializerImpl.serializeClassToIntConverter(stream: OutputStream) {
  val converterMap = ClassToIntConverter.INSTANCE.getMap().toMap()
  val output = Output(stream, KRYO_BUFFER_SIZE)
  try {
    val (kryo, _) = createKryo()

    // Save version
    output.writeString(serializerDataFormatVersion)
    saveContributedVersions(kryo, output)

    val mapData = converterMap.map { (key, value) -> key.typeInfo to value }

    kryo.writeClassAndObject(output, mapData)
  }
  finally {
    flush(output)
  }
}

internal fun reportConsistencyIssue(message: String,
                                    e: Throwable,
                                    sourceFilter: ((EntitySource) -> Boolean)?,
                                    left: EntityStorage?,
                                    right: EntityStorage?,
                                    resulting: EntityStorage) {
  var finalMessage = "$message\n\n"
  finalMessage += "\nVersion: ${EntityStorageSerializerImpl.SERIALIZER_VERSION}"

  val zipFile = if (ConsistencyCheckingMode.current != ConsistencyCheckingMode.DISABLED) {
    val dumpDirectory = getStoreDumpDirectory()
    finalMessage += "\nSaving store content at: $dumpDirectory"
    serializeContentToFolder(dumpDirectory, left, right, resulting, sourceFilter)
  }
  else null

  if (zipFile != null) {
    val attachment = Attachment("workspaceModelDump.zip", zipFile.readBytes(), "Zip of workspace model store")
    attachment.isIncluded = true
    AbstractEntityStorage.LOG.error(finalMessage, e, attachment)
  }
  else {
    AbstractEntityStorage.LOG.error(finalMessage, e)
  }
}

private fun isWrapped(): Boolean = Registry.`is`("ide.new.project.model.report.wrapped", true)
