/*
 * Copyright 2014-2015 Branko Juric, Brady Wood
 * 
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

package gwen.report

import java.io.BufferedInputStream
import java.io.File
import scala.io.Source
import scala.reflect.io.Path
import com.typesafe.scalalogging.slf4j.LazyLogging
import gwen.GwenInfo
import gwen.Predefs.FileIO
import gwen.Predefs.Kestrel
import gwen.dsl.FeatureSpec
import gwen.eval.FeatureResult
import gwen.eval.FeatureSummary
import gwen.eval.GwenOptions
import gwen.eval.DataRecord
import gwen.Predefs.FileIO
import gwen.eval.FeatureUnit

/**
  * Base class for report generators.
  * 
  * @author Branko Juric
  */
class ReportGenerator (
    private val reportFormat: ReportFormat.Value,
    private val options: GwenOptions) extends LazyLogging {
  formatter: ReportFormatter => 

  private[report] val reportDir = reportFormat.reportDir(options) tap { dir =>
    if (!dir.exists) {
      Path(dir).createDirectory()
    }
  }
  
  private val summaryReportFile = reportFormat.summaryFilename.map(name => new File(reportDir, s"$name.${reportFormat.fileExtension}"))
    
  /**
    * Generate and return a detail feature report.
    * 
    * @param info the gwen implementation info
    * @param specs the list of evaluated specs (head = feature spec, tail = meta specs)
    * @param dataRecord optional data record
    * @return the report file
    */
  final def reportDetail(info: GwenInfo, unit: FeatureUnit, specs: List[FeatureSpec]): Option[(ReportFormat.Value, File)] = {
    val (featureSpec::metaSpecs) = specs
    val dataRecord = unit.dataRecord
    val reportFile = reportFormat.createReportFile(reportFormat.createReportDir(options, featureSpec, dataRecord), "", featureSpec, dataRecord) tap { file =>
      reportFeatureDetail(info, unit, featureSpec, file, reportMetaDetail(info, unit, metaSpecs, file))
    }
    Some((reportFormat, reportFile))
  }
  
  private[report] def reportMetaDetail(info: GwenInfo, unit: FeatureUnit, metaSpecs: List[FeatureSpec], featureReportFile: File): List[FeatureResult] = {
    metaSpecs.zipWithIndex map { case (metaspec, idx) =>
      val prefix = s"${ReportGenerator.encodeNo(idx + 1)}-"
      val file = reportFormat.createReportFile(new File(Path(featureReportFile.getParentFile() + File.separator + "meta").createDirectory().path), prefix, metaspec, unit.dataRecord) 
      FeatureResult(metaspec, Some(Map(reportFormat -> file)), Nil) tap { metaResult =>
        val featureCrumb = ("Feature", featureReportFile)
        val breadcrumbs = summaryReportFile.map(f => List(("Summary", f), featureCrumb)).getOrElse(List(featureCrumb))
        formatDetail(options, info, unit, metaResult, breadcrumbs) foreach { content => 
          file.writeText(content) 
          logger.info(s"${reportFormat.name} meta detail report generated: ${file.getAbsolutePath()}")
        }
      }
    }
  }
  
  private final def reportFeatureDetail(info: GwenInfo, unit: FeatureUnit, spec: FeatureSpec, featureReportFile: File, metaResults: List[FeatureResult]) { 
    FeatureResult(spec, Some(Map(reportFormat -> featureReportFile)), metaResults) tap { featureResult =>
      formatDetail(options, info, unit, featureResult, summaryReportFile.map(f => List(("Summary", f))).getOrElse(Nil)) foreach { content =>
        featureReportFile.writeText(content)
        reportAttachments(spec, featureReportFile)
        logger.info(s"${reportFormat.name} feature detail report generated: ${featureReportFile.getAbsolutePath()}")
      }
    }
  }
  
  def reportAttachments(spec: FeatureSpec, featureReportFile: File): Unit = {
    val attachmentsDir = new File(Path(new File(featureReportFile.getParentFile(), "attachments")).createDirectory().path)
    spec.scenarios.flatMap(_.steps).flatMap(_.attachments ) foreach { case (_, file) =>
      new File(attachmentsDir, file.getName()).writeFile(file)
    }
  }
  
  /**
    * Must be implemented to generate and return a summary report file.
    * 
    * @param info the gwen info
    * @param summary the feature summary to report
    */
  final def reportSummary(info: GwenInfo, summary: FeatureSummary): Option[File] =
    if (summary.summaryLines.nonEmpty) {
      summaryReportFile tap { reportFile =>
        reportFile foreach { file =>
          formatSummary(options, info, summary) foreach { content =>
            file.writeText(content)
            logger.info(s"${reportFormat.name} feature summary report generated: ${file.getAbsolutePath()}")
          }
        }
      }
    } else {
      None
    }
   
  private[report] def copyClasspathTextResourceToFile(resource: String, targetDir: File) = 
    new File(targetDir, new File(resource).getName) tap { file =>
      file.writeText(Source.fromInputStream(getClass().getResourceAsStream(resource)).mkString)
    }
  
  private[report] def copyClasspathBinaryResourceToFile(resource: String, targetDir: File) = 
    new File(targetDir, new File(resource).getName) tap { file =>
      file.writeBinary(new BufferedInputStream(getClass().getResourceAsStream(resource)))
    }
  
}

object ReportGenerator {
  
  def generatorsFor(options: GwenOptions): List[ReportGenerator] = {
    options.reportDir foreach { dir =>
      if (dir.exists) {
        dir.renameTo(new File(s"${dir.getAbsolutePath()}-${System.currentTimeMillis()}"))
      }
      Path(dir).createDirectory()
    }
    val formats = 
      if (options.reportFormats.contains(ReportFormat.html)) 
        ReportFormat.slideshow :: options.reportFormats 
      else options.reportFormats
    options.reportDir.map(_ => formats.map(_.reportGenerator(options))).getOrElse(Nil)
  }
  
  def encodeNo(num: Int) = "%04d".format(num)
  
  def encodeDataRecordNo(dataRecord: Option[DataRecord]) = dataRecord.map(record => s"${encodeNo(record.recordNo)}-").getOrElse("")
  
}
