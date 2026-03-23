package org.softwood.dag.office

import org.junit.jupiter.api.Test
import org.softwood.dag.task.DocxAdapterFactory
import org.softwood.dag.task.DocxTask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.docx.DocumentPlan
import org.softwood.dag.task.docx.SectionSpec
import org.softwood.dag.task.docx.TableSpec

import static org.junit.jupiter.api.Assertions.*

/**
 * Smoke test: DocxAdapter GENERATE → file exists → EXTRACT_TEXT contains heading.
 * Calls the injected docxCallFn directly (bypasses Promise machinery).
 */
class DocxTaskSmokeTest {

    @Test
    void smokeGenerateAndExtract() {
        // ---- Setup ----
        TaskContext ctx  = new TaskContext()
        DocxTask    task = new DocxTask('smoke-docx', 'Smoke DOCX', ctx)
        DocxAdapterFactory.inject(task)
        assertNotNull(task.docxCallFn, 'docxCallFn must be injected')

        // ---- Build plan ----
        DocumentPlan plan = new DocumentPlan(
            title     : 'Smoke Test Report',
            properties: [author: 'SmokeTest', subject: 'CI Verification'],
            sections  : [
                new SectionSpec(
                    heading     : 'Executive Summary',
                    headingLevel: 1,
                    text        : 'Revenue grew 12% year-over-year in Q3.'
                ),
                new SectionSpec(
                    heading     : 'Regional Breakdown',
                    headingLevel: 2,
                    table       : new TableSpec(
                        headers  : ['Region', 'Revenue', 'Growth'],
                        rows     : [
                            ['North', '1.2M', '15%'],
                            ['South', '0.9M', '8%']
                        ],
                        headerRow: true
                    )
                ),
                new SectionSpec(
                    heading    : 'Key Risks',
                    headingLevel: 2,
                    bulletList : ['Supply chain volatility', 'FX headwinds', 'Regulatory changes']
                )
            ]
        )

        File output = File.createTempFile('smoke-docx-', '.docx')
        output.deleteOnExit()

        // ---- GENERATE ----
        task.docxCallFn.call('GENERATE', [filePath: output.absolutePath, documentPlan: plan])
        assertTrue(output.exists(),     'Generated .docx file must exist')
        assertTrue(output.length() > 0, 'Generated .docx file must be non-empty')

        // ---- EXTRACT_TEXT ----
        String text = task.docxCallFn.call('EXTRACT_TEXT', [filePath: output.absolutePath]) as String
        assertNotNull(text, 'EXTRACT_TEXT must return a String')
        assertFalse(text.isEmpty(), 'Extracted text must be non-empty')
        assertTrue(text.contains('Executive Summary'),
                   "Extracted text must contain 'Executive Summary' heading")
    }
}
