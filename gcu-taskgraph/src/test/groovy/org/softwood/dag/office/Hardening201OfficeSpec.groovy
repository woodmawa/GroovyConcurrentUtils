package org.softwood.dag.office

import groovy.transform.CompileDynamic
import org.apache.poi.ss.usermodel.ComparisonOperator
import org.apache.poi.ss.usermodel.ConditionType
import org.apache.poi.ss.usermodel.DataValidationConstraint
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFAutoShape
import org.apache.poi.xslf.usermodel.XSLFConnectorShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XSLFTextBox
import org.apache.poi.sl.usermodel.Placeholder
import org.softwood.dag.task.docx.DocumentPlan
import org.softwood.dag.task.docx.DocxAdapter
import org.softwood.dag.task.docx.PageSpec
import org.softwood.dag.task.docx.SectionSpec
import org.softwood.dag.task.docx.TableSpec
import org.softwood.dag.task.pptx.PptxAdapter
import org.softwood.dag.task.pptx.PresentationPlan
import org.softwood.dag.task.pptx.ShapeSpec
import org.softwood.dag.task.pptx.ShapeType
import org.softwood.dag.task.pptx.SlideLayoutSpec
import org.softwood.dag.task.pptx.SlidePlan
import org.softwood.dag.task.pptx.TransitionSpec
import org.softwood.dag.task.pptx.TransitionType
import org.softwood.dag.task.xlsx.AlignmentSpec
import org.softwood.dag.task.xlsx.CellSpec
import org.softwood.dag.task.xlsx.ColumnSpec
import org.softwood.dag.task.xlsx.CommentSpec
import org.softwood.dag.task.xlsx.ConditionalFormatSpec
import org.softwood.dag.task.xlsx.DataRowsSpec
import org.softwood.dag.task.xlsx.DataValidationSpec
import org.softwood.dag.task.xlsx.FillSpec
import org.softwood.dag.task.xlsx.FontSpec
import org.softwood.dag.task.xlsx.GroupSpec
import org.softwood.dag.task.xlsx.QueryPlan
import org.softwood.dag.task.xlsx.RangeSpec
import org.softwood.dag.task.xlsx.RowSpec
import org.softwood.dag.task.xlsx.SheetPlan
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.WorkbookPlan
import org.softwood.dag.task.xlsx.XlsxAdapter
import org.softwood.dag.task.xlsx.XlsxWorkbookDsl
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.TaskFactory
import org.softwood.dag.task.XlsxTask
import org.softwood.dag.task.office.OfficeFiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.nio.file.Path

/**
 * Hardening201OfficeSpec -- GCU 2.0.1.
 *
 * The office adapters accepted plan fields they then ignored: a row style never reached its cells, range data
 * validation and conditional formatting were never applied, pptx speaker notes were dropped behind a caught
 * exception, a named pptx layout was never found, docx headings had no heading styles and list items no
 * numbering. Found by driving the adapters from FS (session 2026-11-23-11-14). Each feature here opens the file
 * the adapter wrote with POI and checks the field reached it; a field the adapter cannot apply must be refused
 * by name before any file is written, never silently dropped.
 */
@CompileDynamic
@Timeout(60)
class Hardening201OfficeSpec extends Specification {

    @TempDir Path tmp

    // ------------------------------------------------------------------ helpers

    private File file(String name) { tmp.resolve(name).toFile() }

    private File xlsx(WorkbookPlan plan, Map extra = [:]) {
        File f = file('out.xlsx')
        XlsxAdapter.asFn().call('GENERATE', [filePath: f.absolutePath, plan: plan] + extra)
        f
    }

    private static XSSFWorkbook openXlsx(File f) { f.withInputStream { new XSSFWorkbook(it) } }

    private static StyleSpec bold() { new StyleSpec(font: new FontSpec(bold: true)) }

    private static boolean isBold(XSSFWorkbook wb, XSSFCell c) { wb.getFontAt(c.cellStyle.fontIndex).bold }

    private static WorkbookPlan oneSheet(Map sheet, Map styles = [:], Map options = [:]) {
        new WorkbookPlan(styles: styles, options: options, sheets: [new SheetPlan([name: 'S'] + sheet)])
    }

    private static List<RowSpec> rows(List<List> values) {
        values.collect { List vs -> new RowSpec(cells: vs.collect { new CellSpec(value: it) }) }
    }

    private File docx(DocumentPlan plan) {
        File f = file('out.docx')
        DocxAdapter.asFn().call('GENERATE', [filePath: f.absolutePath, documentPlan: plan])
        f
    }

    private static XWPFDocument openDocx(File f) { f.withInputStream { new XWPFDocument(it) } }

    private File pptx(PresentationPlan plan) {
        File f = file('out.pptx')
        PptxAdapter.asFn().call('GENERATE', [filePath: f.absolutePath, presentationPlan: plan])
        f
    }

    private static XMLSlideShow openPptx(File f) { f.withInputStream { new XMLSlideShow(it) } }

    private File png() {
        File f = file('dot.png')
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(img, 'png', f)
        f
    }

    // ------------------------------------------------------------------ xlsx

    def "a query naming a column that is not there is refused by name, and known names and letters still project"() {
        given:
        File f = xlsx(oneSheet(rows: rows([['name', 'n'], ['a', 1]])))

        when:
        List ok = XlsxAdapter.asFn().call('QUERY', [filePath: f.absolutePath,
                query: new QueryPlan(sheetName: 'S', columns: ['n', 'A'])]) as List

        then:
        ok == [[n: 1d, name: 'a']] || ok == [[n: 1, name: 'a']]

        when:
        XlsxAdapter.asFn().call('QUERY', [filePath: f.absolutePath,
                query: new QueryPlan(sheetName: 'S', columns: ['n', 'nmae'])])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('nmae')
        e.message.contains('name')
    }

    def "2.0.2: a map_by_key query returns rows keyed by the key column"() {
        given:
        File f = xlsx(oneSheet(rows: rows([['name', 'n'], ['a', 1], ['b', 2]])))

        when:
        Map keyed = XlsxAdapter.asFn().call('QUERY', [filePath: f.absolutePath,
                query: new QueryPlan(sheetName: 'S', format: 'map_by_key', keyColumn: 'name')]) as Map

        then:
        keyed.keySet() == ['a', 'b'] as Set
        keyed.b.n == 2d
    }

    def "2.0.2: a map_by_key query naming a key column that is not a header is refused by name"() {
        given:
        File f = xlsx(oneSheet(rows: rows([['name', 'n'], ['a', 1]])))

        when:
        XlsxAdapter.asFn().call('QUERY', [filePath: f.absolutePath,
                query: new QueryPlan(sheetName: 'S', format: 'map_by_key', keyColumn: 'nmae')])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('nmae')
    }

    def "2.0.3: a range with only a validation or conditional format creates no empty rows"() {
        given:
        def plan = oneSheet([rows: rows([['name', 'n'], ['a', 1]]),
                             ranges: [new RangeSpec(ref: 'B2:B20', dataValidation: new DataValidationSpec(type: 'integer', operator: 'between', formula1: '0', formula2: '150'),
                                                    conditionalFormat: new ConditionalFormatSpec(type: 'cellValue', operator: 'greaterThan', value1: 40, styleRef: 'b'))]],
                            [b: bold()])

        when:
        XSSFWorkbook wb = openXlsx(xlsx(plan))
        XSSFSheet s = wb.getSheet('S')

        then:
        s.lastRowNum == 1
        s.physicalNumberOfRows == 2
        s.dataValidations.size() == 1
        s.sheetConditionalFormatting.numConditionalFormattings == 1
    }

    def "2.0.3: a query skips rows that are blank in every column it returns"() {
        given: 'a styled range that legitimately creates empty, styled cells below the data'
        File f = xlsx(oneSheet([rows: rows([['name', 'n'], ['a', 1]]), ranges: [new RangeSpec(ref: 'A2:B6', styleRef: 'b')]], [b: bold()]))

        when:
        List all = XlsxAdapter.asFn().call('QUERY', [filePath: f.absolutePath, query: new QueryPlan(sheetName: 'S')]) as List
        Map keyed = XlsxAdapter.asFn().call('QUERY', [filePath: f.absolutePath,
                query: new QueryPlan(sheetName: 'S', format: 'map_by_key', keyColumn: 'name')]) as Map

        then:
        all*.name == ['a']
        keyed.keySet() == ['a'] as Set
    }

    def "2.0.3: a map_by_key row with no key is refused, naming the spreadsheet row"() {
        given:
        File f = xlsx(oneSheet(rows: rows([['name', 'n'], ['a', 1], [null, 2]])))

        when:
        XlsxAdapter.asFn().call('QUERY', [filePath: f.absolutePath,
                query: new QueryPlan(sheetName: 'S', format: 'map_by_key', keyColumn: 'name')])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('row 3')
        e.message.contains('name')
    }

    def "a row styleRef reaches the cells written in that row, and a cell styleRef wins"() {
        given:
        def plan = oneSheet([rows: [new RowSpec(styleRef: 'b', cells: [new CellSpec(value: 'h1'), new CellSpec(value: 'h2', styleRef: 'plain')])]],
                            [b: bold(), plain: new StyleSpec()])

        when:
        XSSFWorkbook wb = openXlsx(xlsx(plan))
        XSSFSheet s = wb.getSheet('S')

        then:
        isBold(wb, s.getRow(0).getCell(0))
        !isBold(wb, s.getRow(0).getCell(1))

        cleanup:
        wb?.close()
    }

    def "a sheet defaultStyle applies to cells with no style of their own"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([defaultStyle: 'b', rows: rows([['x']])], [b: bold()])))

        then:
        isBold(wb, wb.getSheet('S').getRow(0).getCell(0))

        cleanup:
        wb?.close()
    }

    def "a column styleRef and outlineLevel are applied"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([columns: [new ColumnSpec(ref: 'B', styleRef: 'b', outlineLevel: 1)], rows: rows([['a']])], [b: bold()])))
        XSSFSheet s = wb.getSheet('S')

        then:
        s.getColumnStyle(1) != null
        wb.getFontAt(s.getColumnStyle(1).fontIndex).bold
        s.getColumnOutlineLevel(1) == 1

        cleanup:
        wb?.close()
    }

    def "a list data validation is written on its range"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['h'], ['Active']]),
            ranges: [new RangeSpec(ref: 'A2:A5', dataValidation: new DataValidationSpec(type: 'list', list: ['Active', 'Closed']))]])))
        def v = wb.getSheet('S').dataValidations

        then:
        v.size() == 1
        v[0].validationConstraint.explicitListValues as List == ['Active', 'Closed']
        v[0].regions.cellRangeAddresses[0].formatAsString() == 'A2:A5'

        cleanup:
        wb?.close()
    }

    def "a decimal data validation keeps its operator and bounds"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['h']]),
            ranges: [new RangeSpec(ref: 'B2:B9', dataValidation: new DataValidationSpec(type: 'decimal', operator: 'between', formula1: '0', formula2: '100'))]])))
        def c = wb.getSheet('S').dataValidations[0].validationConstraint

        then:
        c.validationType == DataValidationConstraint.ValidationType.DECIMAL
        c.operator == DataValidationConstraint.OperatorType.BETWEEN
        c.formula1 == '0'
        c.formula2 == '100'

        cleanup:
        wb?.close()
    }

    def "a cellValue conditional format is written with its style"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['h'], [150], [50]]),
            ranges: [new RangeSpec(ref: 'A2:A3', conditionalFormat: new ConditionalFormatSpec(type: 'cellValue', operator: 'greaterThan', value1: 100, styleRef: 'b'))]],
            [b: bold()])))
        def cf = wb.getSheet('S').sheetConditionalFormatting

        then:
        cf.numConditionalFormattings == 1
        def rule = cf.getConditionalFormattingAt(0).getRule(0)
        rule.conditionType == ConditionType.CELL_VALUE_IS
        rule.comparisonOperation == ComparisonOperator.GT
        rule.formula1 == '100'
        rule.fontFormatting?.bold

        cleanup:
        wb?.close()
    }

    def "a formula conditional format is written"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['h'], [1]]),
            ranges: [new RangeSpec(ref: 'A2:A9', conditionalFormat: new ConditionalFormatSpec(type: 'formula', formula: 'MOD(ROW(),2)=0', styleRef: 'b'))]],
            [b: bold()])))
        def rule = wb.getSheet('S').sheetConditionalFormatting.getConditionalFormattingAt(0).getRule(0)

        then:
        rule.conditionType == ConditionType.FORMULA
        rule.formula1 == 'MOD(ROW(),2)=0'

        cleanup:
        wb?.close()
    }

    def "colorScale, dataBar and iconSet conditional formats are written"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['h'], [1], [2], [3]]),
            ranges: [new RangeSpec(ref: 'A2:A4', conditionalFormat: new ConditionalFormatSpec(type: 'colorScale')),
                     new RangeSpec(ref: 'B2:B4', conditionalFormat: new ConditionalFormatSpec(type: 'dataBar')),
                     new RangeSpec(ref: 'C2:C4', conditionalFormat: new ConditionalFormatSpec(type: 'iconSet'))]])))
        def cf = wb.getSheet('S').sheetConditionalFormatting
        List types = (0..<cf.numConditionalFormattings).collect { cf.getConditionalFormattingAt(it).getRule(0).conditionType }

        then:
        types as Set == [ConditionType.COLOR_SCALE, ConditionType.DATA_BAR, ConditionType.ICON_SET] as Set

        cleanup:
        wb?.close()
    }

    def "a range comment lands on the range's first cell"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['a', 'b']]), ranges: [new RangeSpec(ref: 'A1:B1', comment: 'check these')]])))

        then:
        wb.getSheet('S').getRow(0).getCell(0).cellComment?.string?.string == 'check these'

        cleanup:
        wb?.close()
    }

    def "zebraStripe fills alternate rows of the range"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['r1'], ['r2'], ['r3'], ['r4']]), ranges: [new RangeSpec(ref: 'A1:A4', zebraStripe: true)]])))
        XSSFSheet s = wb.getSheet('S')
        List fills = (0..3).collect { s.getRow(it).getCell(0).cellStyle.fillForegroundColorColor?.ARGBHex }

        then:
        fills[0] == null
        fills[1] == 'FFF2F2F2'
        fills[2] == null
        fills[3] == 'FFF2F2F2'

        cleanup:
        wb?.close()
    }

    def "a cell imagePath embeds the picture at that cell"() {
        given:
        File img = png()

        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: [new RowSpec(cells: [new CellSpec(ref: 'B1', imagePath: img.absolutePath)])]])))

        then:
        wb.allPictures.size() == 1
        wb.getSheet('S').drawingPatriarch.shapes[0].clientAnchor.col1 == 1

        cleanup:
        wb?.close()
    }

    def "a missing image file fails the plan instead of being skipped"() {
        when:
        xlsx(oneSheet([rows: [new RowSpec(cells: [new CellSpec(imagePath: file('nope.png').absolutePath)])]]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('nope.png')
    }

    def "deferredRows are filled from config.data"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['name', 'n']]), deferredRows: [new DataRowsSpec(startAt: 2, dataKey: 'people')]]),
                                        [data: [people: [['ann', 1], ['bob', 2]]]]))
        XSSFSheet s = wb.getSheet('S')

        then:
        s.getRow(1).getCell(0).stringCellValue == 'ann'
        s.getRow(2).getCell(1).numericCellValue == 2d

        cleanup:
        wb?.close()
    }

    def "deferredRows with no data for its key fail by name"() {
        when:
        xlsx(oneSheet([deferredRows: [new DataRowsSpec(dataKey: 'missing')]]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('missing')
        !file('out.xlsx').exists()
    }

    def "row groups accept a two-element list, the shape JSON gives them"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: rows([['a'], ['b'], ['c'], ['d']]), rowGroups: [new GroupSpec(range: [2, 3])]])))

        then:
        wb.getSheet('S').getRow(1).outlineLevel == 1
        wb.getSheet('S').getRow(2).outlineLevel == 1
        wb.getSheet('S').getRow(3).outlineLevel == 0

        cleanup:
        wb?.close()
    }

    def "evaluateFormulas stores the computed value"() {
        when:
        XSSFWorkbook wb = openXlsx(xlsx(oneSheet([rows: [new RowSpec(cells: [new CellSpec(value: 2), new CellSpec(value: 3), new CellSpec(formula: 'A1+B1')])]], [:], [evaluateFormulas: true])))

        then:
        wb.getSheet('S').getRow(0).getCell(2).numericCellValue == 5d

        cleanup:
        wb?.close()
    }

    def "an option the adapter cannot apply is refused by name before any file is written"() {
        when:
        xlsx(oneSheet([rows: rows([['x']])], [:], [sharedStrings: false]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('sharedStrings')
        !file('out.xlsx').exists()
    }

    // ------------------------------------------------------------------ docx

    def "headings use heading styles the document defines"() {
        when:
        XWPFDocument d = openDocx(docx(new DocumentPlan(sections: [new SectionSpec(heading: 'Top', text: 'x'), new SectionSpec(heading: 'Sub', headingLevel: 2)])))

        then:
        d.styles.styleExist('Heading1')
        d.styles.styleExist('Heading2')
        d.paragraphs.find { it.text == 'Top' }.style == 'Heading1'

        cleanup:
        d?.close()
    }

    def "bullet and numbered list items carry list numbering"() {
        when:
        XWPFDocument d = openDocx(docx(new DocumentPlan(sections: [new SectionSpec(bulletList: ['b1', 'b2'], numberedList: ['n1'])])))
        XWPFParagraph b1 = d.paragraphs.find { it.text == 'b1' }
        XWPFParagraph n1 = d.paragraphs.find { it.text == 'n1' }

        then:
        b1.numID != null
        b1.numFmt == 'bullet'
        n1.numID != null
        n1.numFmt == 'decimal'
        b1.numID != n1.numID

        cleanup:
        d?.close()
    }

    def "a table's headerStyle, bodyStyle and columnWidths are applied"() {
        given:
        def t = new TableSpec(headers: ['a', 'b'], rows: [['1', '2']], columnWidths: [2000, 3000],
                              headerStyle: new StyleSpec(font: new FontSpec(bold: true, color: '#FFFFFF'), fill: new FillSpec(foreground: '#1F4E78')),
                              bodyStyle: new StyleSpec(font: new FontSpec(italic: true)))

        when:
        XWPFDocument d = openDocx(docx(new DocumentPlan(sections: [new SectionSpec(table: t)])))
        def table = d.tables[0]
        def hdr = table.getRow(0).getCell(0)
        def body = table.getRow(1).getCell(0)

        then:
        hdr.color == '1F4E78'
        hdr.paragraphs[0].runs[0].bold
        hdr.paragraphs[0].runs[0].color == 'FFFFFF'
        body.paragraphs[0].runs[0].italic
        table.CTTbl.tblGrid.gridColList*.w*.toString() == ['2000', '3000']

        cleanup:
        d?.close()
    }

    def "a section style's font reaches its text"() {
        when:
        XWPFDocument d = openDocx(docx(new DocumentPlan(sections: [new SectionSpec(text: 'styled',
            style: new StyleSpec(font: new FontSpec(bold: true, size: 14, name: 'Arial', color: '#FF0000'), alignment: new AlignmentSpec(horizontal: 'center')))])))
        def run = d.paragraphs.find { it.text == 'styled' }.runs[0]

        then:
        run.bold
        run.fontSizeAsDouble == 14d
        run.fontFamily == 'Arial'
        run.color == 'FF0000'

        cleanup:
        d?.close()
    }

    def "a landscape page setup is applied"() {
        when:
        XWPFDocument d = openDocx(docx(new DocumentPlan(page: new PageSpec(size: 'A4', orientation: 'landscape'), sections: [new SectionSpec(text: 'x')])))
        def pgSz = d.document.body.sectPr.pgSz

        then:
        pgSz.orient.toString() == 'landscape'
        (pgSz.w as BigInteger) > (pgSz.h as BigInteger)

        cleanup:
        d?.close()
    }

    def "title and document properties are set, category and company included"() {
        when:
        XWPFDocument d = openDocx(docx(new DocumentPlan(title: 'T', properties: [author: 'will', subject: 'sub', category: 'cat', company: 'softwood'],
                                                        sections: [new SectionSpec(text: 'x')])))
        def cp = d.properties.coreProperties

        then:
        cp.title == 'T'
        cp.creator == 'will'
        cp.subject == 'sub'
        cp.category == 'cat'
        d.properties.extendedProperties.company == 'softwood'

        cleanup:
        d?.close()
    }

    // ------------------------------------------------------------------ pptx

    def "speaker notes are written, and READ returns them"() {
        when:
        File f = pptx(new PresentationPlan(slides: [new SlidePlan(title: 'S1', notes: 'say this')]))
        XMLSlideShow s = openPptx(f)
        def notesBody = s.getNotesSlide(s.slides[0]).placeholders.find { it.textType == Placeholder.BODY }
        Map read = PptxAdapter.asFn().call('READ', [filePath: f.absolutePath]) as Map

        then:
        notesBody?.text == 'say this'
        read.slides[0].notes == 'say this'

        cleanup:
        s?.close()
    }

    def "a named layout is used"() {
        when:
        XMLSlideShow s = openPptx(pptx(new PresentationPlan(slides: [new SlidePlan(title: 'S1', layout: 'Title and Content')])))

        then:
        s.slides[0].slideLayout.name == 'Title and Content'

        cleanup:
        s?.close()
    }

    def "an unknown layout name fails by name"() {
        when:
        pptx(new PresentationPlan(slides: [new SlidePlan(layout: 'No Such Layout')]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('No Such Layout')
        !file('out.pptx').exists()
    }

    def "presentation title and properties are set"() {
        when:
        XMLSlideShow s = openPptx(pptx(new PresentationPlan(title: 'Deck', properties: [author: 'will', subject: 'sub'], slides: [new SlidePlan(title: 'x')])))
        def cp = s.properties.coreProperties

        then:
        cp.title == 'Deck'
        cp.creator == 'will'
        cp.subject == 'sub'

        cleanup:
        s?.close()
    }

    def "RECTANGLE and OVAL are auto-shapes with their fill and text, and LINE is a line"() {
        given:
        def fill = new StyleSpec(fill: new FillSpec(foreground: '#00FF00'))

        when:
        XMLSlideShow s = openPptx(pptx(new PresentationPlan(slides: [new SlidePlan(shapes: [
            new ShapeSpec(type: ShapeType.RECTANGLE, text: 'box', style: fill, x: 0, y: 0, width: 914400, height: 914400),
            new ShapeSpec(type: ShapeType.OVAL, x: 914400, y: 0, width: 914400, height: 914400),
            new ShapeSpec(type: ShapeType.LINE, x: 0, y: 1828800, width: 1828800, height: 0)])])))
        def shapes = s.slides[0].shapes
        // a text box is itself a RECT auto-shape in POI: exclude it, so only a real rectangle can satisfy this
        def rect = shapes.find { it instanceof XSLFAutoShape && !(it instanceof XSLFTextBox) && it.shapeType == org.apache.poi.sl.usermodel.ShapeType.RECT }
        def oval = shapes.find { it instanceof XSLFAutoShape && it.shapeType == org.apache.poi.sl.usermodel.ShapeType.ELLIPSE }

        then:
        rect != null
        rect.text == 'box'
        rect.fillColor == new java.awt.Color(0, 255, 0)
        oval != null
        shapes.any { it instanceof XSLFConnectorShape }

        cleanup:
        s?.close()
    }

    def "a text shape's bold, italic, font name and fill are applied"() {
        when:
        XMLSlideShow s = openPptx(pptx(new PresentationPlan(slides: [new SlidePlan(shapes: [new ShapeSpec(text: 'hi', width: 914400, height: 457200,
            style: new StyleSpec(font: new FontSpec(bold: true, italic: true, name: 'Arial'), fill: new FillSpec(foreground: '#FFFF00')))])])))
        XSLFTextBox tb = s.slides[0].shapes.find { it instanceof XSLFTextBox && it.text == 'hi' }
        def run = tb.textParagraphs[0].textRuns[0]

        then:
        run.bold
        run.italic
        run.fontFamily == 'Arial'
        tb.fillColor == new java.awt.Color(255, 255, 0)

        cleanup:
        s?.close()
    }

    def "defaultLayout background and fonts reach every slide"() {
        when:
        XMLSlideShow s = openPptx(pptx(new PresentationPlan(defaultLayout: new SlideLayoutSpec(background: '#000080', fontFamily: 'Georgia', titleFontSize: 40, bodyFontSize: 20),
            slides: [new SlidePlan(title: 'T1', shapes: [new ShapeSpec(text: 'body', width: 914400, height: 457200)]), new SlidePlan(title: 'T2')])))
        XSLFSlide first = s.slides[0]
        def titleRun = first.shapes.find { it instanceof XSLFTextShape && it.text == 'T1' }.textParagraphs[0].textRuns[0]
        def bodyRun  = first.shapes.find { it instanceof XSLFTextShape && it.text == 'body' }.textParagraphs[0].textRuns[0]

        then:
        s.slides.every { it.background.fillColor == new java.awt.Color(0, 0, 128) }
        titleRun.fontFamily == 'Georgia'
        titleRun.fontSize == 40d
        bodyRun.fontFamily == 'Georgia'
        bodyRun.fontSize == 20d

        cleanup:
        s?.close()
    }

    def "an IMAGE shape whose file is missing fails instead of being skipped"() {
        when:
        pptx(new PresentationPlan(slides: [new SlidePlan(shapes: [new ShapeSpec(type: ShapeType.IMAGE, imagePath: file('gone.png').absolutePath)])]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('gone.png')
    }

    def "a transition's duration sets its speed"() {
        when:
        XMLSlideShow s = openPptx(pptx(new PresentationPlan(slides: [new SlidePlan(title: 'x', transition: new TransitionSpec(type: TransitionType.FADE, durationMs: 1500))])))

        then:
        s.slides[0].xmlObject.transition.spd.toString() == 'slow'

        cleanup:
        s?.close()
    }

    def "a pptx field the adapter cannot apply is refused by name before any file is written: #field"() {
        when:
        pptx(plan)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(field)
        !file('out.pptx').exists()

        where:
        field         | plan
        'theme'       | new PresentationPlan(defaultLayout: new SlideLayoutSpec(theme: 'Office Theme'), slides: [new SlidePlan(title: 'x')])
        'accentColor' | new PresentationPlan(defaultLayout: new SlideLayoutSpec(accentColor: '#FF0000'), slides: [new SlidePlan(title: 'x')])
        'styles'      | new PresentationPlan(styles: [h: new StyleSpec()], slides: [new SlidePlan(title: 'x')])
        'CHART'       | new PresentationPlan(slides: [new SlidePlan(shapes: [new ShapeSpec(type: ShapeType.CHART)])])
    }

    // ------------------------------------------------------------------ atomic writes

    def "a save that fails part-way leaves the existing file untouched and no temporary file behind"() {
        given:
        File f = file('keep.xlsx')
        f.text = 'original'

        when:
        OfficeFiles.writeAtomically(f) { OutputStream out -> out.write('half'.bytes); throw new IOException('boom') }

        then:
        thrown(IOException)
        f.text == 'original'
        tmp.toFile().listFiles()*.name == ['keep.xlsx']
    }

    // ------------------------------------------------------------------ the workbook DSL

    def "the documented named-argument DSL forms build the sheet they describe"() {
        given:
        def dsl = new XlsxWorkbookDsl(null, null, null)

        when: "row/column/cell/range are called the way the javadoc shows them"
        dsl.style('h') { font bold: true }
        dsl.sheet('S') {
            row 1, style: 'h', values: ['name', 'n']
            row { cell 'B', formula: 'SUM(B2:B3)' }
            column 'A', width: 12
            cell 'D5', value: 7
            range 'A1:B1', style: 'h'
        }
        SheetPlan sp = dsl.build().sheets[0]

        then:
        sp.rows*.cells*.size() == [2, 1, 1]
        // text in values: is a value, not a column reference
        sp.rows[0].cells*.value == ['name', 'n']
        sp.rows[0].cells*.ref == [null, null]
        sp.rows[0].styleRef == 'h'
        sp.columns[0].ref == 'A'
        sp.ranges[0].ref == 'A1:B1'
    }

    // ------------------------------------------------------------------ XlsxTask

    private static TaskContext ctx(Map sharedFacts = [:]) {
        TaskContext c = new TaskContext()
        c.globals.put('__workingMemory', [sharedFacts: sharedFacts, runContext: [:]])
        c
    }

    private static Object run(XlsxTask t, TaskContext c) {
        t.execute(c.promiseFactory.createPromise(null)).get()
    }

    private static String messages(Throwable t) {
        List<String> m = []
        while (t) { m << String.valueOf(t.message); t = t.cause }
        m.join(' | ')
    }

    private static List<List> sheetRows(File f, String sheet) {
        XSSFWorkbook wb = openXlsx(f)
        try {
            def s = wb.getSheet(sheet)
            (0..s.lastRowNum).collect { int r -> s.getRow(r) ? (0..<s.getRow(r).lastCellNum).collect { s.getRow(r).getCell(it)?.toString() } : [] }
        } finally {
            wb.close()
        }
    }

    def "an XlsxTask workbook { } writes its plan, with deferred rows from working memory"() {
        given:
        TaskContext c = ctx([people: [['ann', 1], ['bob', 2]]])
        XlsxTask t = TaskFactory.createXlsxTask('x', 'x', c)
        t.filePath = file('task.xlsx').absolutePath
        t.workbook {
            sheet('People') {
                row 1, values: ['name', 'n']
                rows dataKey: 'people', startAt: 2
            }
        }

        when:
        run(t, c)

        then:
        sheetRows(file('task.xlsx'), 'People') == [['name', 'n'], ['ann', '1.0'], ['bob', '2.0']]
    }

    def "XlsxTask CREATE writes headers and rows"() {
        given:
        TaskContext c = ctx()
        XlsxTask t = TaskFactory.createXlsxTask('x', 'x', c)
        t.with { action = 'CREATE'; filePath = file('create.xlsx').absolutePath; sheetName = 'S'; headers = ['a', 'b']; rows = [['1', '2']] }

        when:
        run(t, c)

        then:
        sheetRows(file('create.xlsx'), 'S') == [['a', 'b'], ['1', '2']]
    }

    def "XlsxTask APPEND adds rows after the sheet's last row"() {
        given:
        TaskContext c = ctx()
        String path = file('append.xlsx').absolutePath
        XlsxTask first = TaskFactory.createXlsxTask('a', 'a', c)
        first.with { action = 'CREATE'; filePath = path; sheetName = 'S'; headers = ['a']; rows = [['r1']] }
        run(first, c)
        XlsxTask t = TaskFactory.createXlsxTask('b', 'b', c)
        t.with { action = 'APPEND'; filePath = path; sheetName = 'S'; rows = [['r2'], ['r3']] }

        when:
        run(t, c)

        then:
        sheetRows(file('append.xlsx'), 'S') == [['a'], ['r1'], ['r2'], ['r3']]
    }

    def "XlsxTask WRITE replaces its sheet and keeps the workbook's other sheets"() {
        given:
        TaskContext c = ctx()
        String path = file('write.xlsx').absolutePath
        ['One', 'Two'].each { String n ->
            XlsxTask w = TaskFactory.createXlsxTask(n, n, c)
            w.with { action = 'WRITE'; filePath = path; sheetName = n; headers = ['h']; rows = [[n + '-old']] }
            run(w, c)
        }
        XlsxTask t = TaskFactory.createXlsxTask('again', 'again', c)
        t.with { action = 'WRITE'; filePath = path; sheetName = 'One'; headers = ['h']; rows = [['One-new']] }

        when:
        run(t, c)

        then:
        sheetRows(file('write.xlsx'), 'One') == [['h'], ['One-new']]
        sheetRows(file('write.xlsx'), 'Two') == [['h'], ['Two-old']]
    }

    def "XlsxTask TRANSFORM rewrites each row through rowTransformer"() {
        given:
        TaskContext c = ctx()
        String path = file('transform.xlsx').absolutePath
        XlsxTask seed = TaskFactory.createXlsxTask('s', 's', c)
        seed.with { action = 'CREATE'; filePath = path; sheetName = 'S'; headers = ['name']; rows = [['ann'], ['bob']] }
        run(seed, c)
        XlsxTask t = TaskFactory.createXlsxTask('t', 't', c)
        t.with { action = 'TRANSFORM'; filePath = path; sheetName = 'S'; rowTransformer = { Map r -> [name: (r.name as String).toUpperCase()] } }

        when:
        run(t, c)

        then:
        sheetRows(file('transform.xlsx'), 'S') == [['name'], ['ANN'], ['BOB']]
    }

    def "XlsxTask useLlmTransform is refused by name, since no LLM transform exists"() {
        given:
        TaskContext c = ctx()
        XlsxTask t = TaskFactory.createXlsxTask('t', 't', c)
        t.with { action = 'TRANSFORM'; filePath = file('llm.xlsx').absolutePath; useLlmTransform = true }

        when:
        run(t, c)

        then:
        def e = thrown(Throwable)
        messages(e).contains('useLlmTransform')
    }
}
