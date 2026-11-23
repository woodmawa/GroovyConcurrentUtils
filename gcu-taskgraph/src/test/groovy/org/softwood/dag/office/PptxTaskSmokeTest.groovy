package org.softwood.dag.office

import org.junit.jupiter.api.Test
import org.softwood.dag.task.PptxAdapterFactory
import org.softwood.dag.task.PptxTask
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.pptx.PresentationPlan
import org.softwood.dag.task.pptx.ShapeSpec
import org.softwood.dag.task.pptx.ShapeType
import org.softwood.dag.task.pptx.SlidePlan

import static org.junit.jupiter.api.Assertions.*

/**
 * Smoke test: PptxAdapter GENERATE → file exists → READ returns slideCount == 2.
 * Calls the injected pptxCallFn directly (bypasses Promise machinery).
 */
class PptxTaskSmokeTest {

    @Test
    void smokeGenerateAndRead() {
        // ---- Setup ----
        TaskContext ctx  = new TaskContext()
        PptxTask    task = new PptxTask('smoke-pptx', 'Smoke PPTX', ctx)
        PptxAdapterFactory.inject(task)
        assertNotNull(task.pptxCallFn, 'pptxCallFn must be injected')

        // ---- Build plan (EMU coords: 914400 EMU = 1 inch) ----
        PresentationPlan plan = new PresentationPlan(
            title : 'Smoke Test Presentation',
            slides: [
                new SlidePlan(
                    title : 'Welcome',
                    notes : 'Presenter notes for slide one',
                    shapes: [
                        new ShapeSpec(
                            type  : ShapeType.TEXT_BOX,
                            text  : 'Smoke test — slide one content',
                            x     : 457200L,  y     : 274638L,
                            width : 8229600L, height: 1143000L
                        )
                    ]
                ),
                new SlidePlan(
                    title : 'Key Points',
                    shapes: [
                        new ShapeSpec(
                            type  : ShapeType.TEXT_BOX,
                            text  : 'Slide two body text: all systems nominal',
                            x     : 457200L,  y     : 1143000L,
                            width : 8229600L, height: 3429000L
                        )
                    ]
                )
            ]
        )

        File output = File.createTempFile('smoke-pptx-', '.pptx')
        output.deleteOnExit()

        // ---- GENERATE ----
        task.pptxCallFn.call('GENERATE', [filePath: output.absolutePath, presentationPlan: plan])
        assertTrue(output.exists(),     'Generated .pptx file must exist')
        assertTrue(output.length() > 0, 'Generated .pptx file must be non-empty')

        // ---- READ ----
        Map readResult = task.pptxCallFn.call('READ', [filePath: output.absolutePath]) as Map
        assertNotNull(readResult, 'READ must return a Map')
        assertEquals(2, readResult.slideCount as int, 'Presentation must have exactly 2 slides')

        List slides = readResult.slides as List
        assertEquals(2, slides.size(), 'slides list must have 2 entries')
    }
}
