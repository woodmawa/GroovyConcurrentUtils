package org.softwood.dag.office

import org.junit.jupiter.api.Test
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.XlsxAdapterFactory
import org.softwood.dag.task.XlsxTask
import org.softwood.dag.task.xlsx.CellSpec
import org.softwood.dag.task.xlsx.FillSpec
import org.softwood.dag.task.xlsx.FontSpec
import org.softwood.dag.task.xlsx.MergeSpec
import org.softwood.dag.task.xlsx.RowSpec
import org.softwood.dag.task.xlsx.SheetPlan
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.WorkbookPlan

import static org.junit.jupiter.api.Assertions.*

/**
 * Smoke test: XlsxAdapter GENERATE → file exists → READ returns expected sheet.
 * Calls the injected xlsxCallFn directly (bypasses Promise machinery).
 */
class XlsxTaskSmokeTest {

    @Test
    void smokeGenerateAndRead() {
        // ---- Setup ----
        TaskContext ctx  = new TaskContext()
        XlsxTask    task = new XlsxTask('smoke-xlsx', 'Smoke XLSX', ctx)
        XlsxAdapterFactory.inject(task)
        assertNotNull(task.xlsxCallFn, 'xlsxCallFn must be injected')

        // ---- Build plan ----
        WorkbookPlan plan = new WorkbookPlan(
            styles: [
                header: new StyleSpec(
                    font : new FontSpec(bold: true, size: 12, color: '#FFFFFF'),
                    fill : new FillSpec(foreground: '#4472C4', pattern: 'solid')
                )
            ],
            sheets: [
                new SheetPlan(
                    name: 'Results',
                    rows: [
                        new RowSpec(rowIndex: 1, styleRef: 'header', cells: [
                            new CellSpec(value: 'Name'),
                            new CellSpec(value: 'Score'),
                            new CellSpec(value: 'Grade')
                        ]),
                        new RowSpec(rowIndex: 2, cells: [
                            new CellSpec(value: 'Alice'),
                            new CellSpec(value: 95.0),
                            new CellSpec(value: 'A')
                        ]),
                        new RowSpec(rowIndex: 3, cells: [
                            new CellSpec(value: 'Bob'),
                            new CellSpec(value: 82.0),
                            new CellSpec(value: 'B')
                        ]),
                        new RowSpec(rowIndex: 4, cells: [
                            new CellSpec(value: 'Carol'),
                            new CellSpec(value: 77.0),
                            new CellSpec(value: 'C')
                        ]),
                        new RowSpec(rowIndex: 5, cells: [
                            new CellSpec(value: 'Average'),
                            new CellSpec(formula: 'AVERAGE(B2:B4)'),
                            new CellSpec(value: '')
                        ])
                    ],
                    merges: [new MergeSpec(ref: 'A6:C6')]
                )
            ]
        )

        File output = File.createTempFile('smoke-xlsx-', '.xlsx')
        output.deleteOnExit()

        // ---- GENERATE ----
        task.xlsxCallFn.call('GENERATE', [filePath: output.absolutePath, plan: plan])
        assertTrue(output.exists(),     'Generated .xlsx file must exist')
        assertTrue(output.length() > 0, 'Generated .xlsx file must be non-empty')

        // ---- READ back ----
        Map readResult = task.xlsxCallFn.call('READ', [filePath: output.absolutePath]) as Map
        assertNotNull(readResult, 'READ must return a Map')
        assertTrue(readResult.containsKey('Results'), "READ must return sheet 'Results'")

        List rows = readResult['Results'] as List
        assertFalse(rows.isEmpty(), 'Results sheet must have data rows')
        // First data row (after header) should have Alice
        Map firstRow = rows[0] as Map
        assertEquals('Alice',  firstRow['Name']  as String)
        assertEquals(95.0d, (firstRow['Score'] as Number).doubleValue(), 0.001d)
    }
}
