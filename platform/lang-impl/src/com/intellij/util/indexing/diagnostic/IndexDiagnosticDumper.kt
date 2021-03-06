// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.indexing.diagnostic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.SystemProperties
import com.intellij.util.indexing.diagnostic.dto.JsonIndexDiagnostic
import com.intellij.util.io.createDirectories
import com.intellij.util.io.delete
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.streams.asSequence

object IndexDiagnosticDumper {

  @JvmStatic
  val shouldDumpDiagnosticsForInterruptedUpdaters: Boolean get() =
    SystemProperties.getBooleanProperty("intellij.indexes.diagnostics.should.dump.for.interrupted.index.updaters", false)

  @JvmStatic
  val indexingDiagnosticsLimitOfFiles: Int get() =
    SystemProperties.getIntProperty("intellij.indexes.diagnostics.limit.of.files", 20)

  val indexingDiagnosticDir: Path by lazy {
    val logPath = PathManager.getLogPath()
    Paths.get(logPath).resolve("indexing-diagnostic")
  }

  private val LOG = Logger.getInstance(IndexDiagnosticDumper::class.java)

  private val jacksonMapper by lazy {
    jacksonObjectMapper().registerKotlinModule().writerWithDefaultPrettyPrinter()
  }

  private val diagnosticDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")

  @Synchronized
  fun dumpProjectIndexingHistoryToLogSubdirectory(projectIndexingHistory: ProjectIndexingHistory) {
    try {
      val indexDiagnosticDirectory = indexingDiagnosticDir
      indexDiagnosticDirectory.createDirectories()

      val fileNamePrefix = "diagnostic-"

      val timestamp = LocalDateTime.now().format(diagnosticDateTimeFormatter)
      val diagnosticJson = indexDiagnosticDirectory.resolve("$fileNamePrefix$timestamp.json")

      val jsonIndexDiagnostic = JsonIndexDiagnostic.generateForHistory(projectIndexingHistory)
      jacksonMapper.writeValue(diagnosticJson.toFile(), jsonIndexDiagnostic)

      val limitOfHistories = indexingDiagnosticsLimitOfFiles
      val survivedHistories = Files.list(indexDiagnosticDirectory).use { files ->
        files.asSequence()
          .filter { it.fileName.toString().startsWith(fileNamePrefix) && it.fileName.toString().endsWith(".json") }
          .sortedByDescending { file ->
            val timeStamp = file.fileName.toString().substringAfter(fileNamePrefix).substringBefore(".json")
            try {
              LocalDateTime.parse(timeStamp, diagnosticDateTimeFormatter)
            }
            catch (e: DateTimeParseException) {
              LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)
            }
          }
          .take(limitOfHistories)
          .toSet()
      }

      Files
        .list(indexDiagnosticDirectory)
        .use { files ->
          files
            .asSequence()
            .filterNot { it in survivedHistories }
            .forEach { it.delete() }
        }
    }
    catch (e: Exception) {
      LOG.warn("Failed to dump index diagnostic", e)
    }
  }

}