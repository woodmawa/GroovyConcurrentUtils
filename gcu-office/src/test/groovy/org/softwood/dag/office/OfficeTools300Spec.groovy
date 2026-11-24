package org.softwood.dag.office

import groovy.transform.CompileDynamic
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.softwood.dag.task.DocxTask
import org.softwood.dag.task.TaskFactory
import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.docx.DocumentPlan
import org.softwood.dag.task.docx.SectionSpec
import org.softwood.dag.task.office.OfficeFiles
import org.softwood.dag.task.office.OfficePlanBinder
import org.softwood.dag.task.office.OfficeTools
import org.softwood.dag.task.spi.OfficeBackends
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * GCU 3.0.0 -- the one office implementation AW and FS share (decision 499).
 *
 * <p>Every case goes through the public closures ({@link OfficeTools#docx()} etc., or TaskFactory through the
 * OfficeBackend SPI), the way the MCP servers call it -- never a private helper. Maps are built in the shapes a JSON
 * transport delivers: keys sorted, order carried only by lists.</p>
 */
@CompileDynamic
class OfficeTools300Spec extends Specification {

    @TempDir Path dir

    private String f(String name) { dir.resolve(name).toString() }

    private static Map sorted(Map m) { new TreeMap(m) }   // what a JSON transport hands over

    // ------------------------------------------------------------------ SPI wiring

    def "TaskFactory office tasks are wired to gcu-office through the OfficeBackend SPI"() {
        given:
        DocxTask task = TaskFactory.createDocxTask('d1', 'd1', new TaskContext())
        String p = f('spi.docx')

        when:
        task.docxCallFn.call('GENERATE', [filePath: p, documentPlan: new DocumentPlan(sections: [new SectionSpec(heading: 'H', text: 't')])])

        then:
        OfficeBackends.available()
        new File(p).exists()
    }

    // ------------------------------------------------------------------ docx

    def "docx GENERATE takes ordered sections in every form and keeps their order"() {
        when:
        OfficeTools.docx().call('GENERATE', sorted([filePath: f('a.docx'), content: content]))
        Map read = OfficeTools.docx().call('READ', [filePath: f('a.docx')]) as Map

        then:
        read.sections*.heading == ['Intro', 'Plan', 'Close']
        read.sections*.content == ['hello', '\u2022 a\n\u2022 b', 'bye']

        where:
        content << [
            [[body: 'hello', heading: 'Intro'], [body: ['a', 'b'], heading: 'Plan'], [body: 'bye', heading: 'Close']],
            [['Intro', 'hello'], ['Plan', ['a', 'b']], ['Close', 'bye']],
            new LinkedHashMap([Intro: 'hello', Plan: ['a', 'b'], Close: 'bye']),
        ]
    }

    def "docx plain GENERATE gives headings a style the document defines (they were body text in Word)"() {
        when:
        OfficeTools.docx().call('GENERATE', [filePath: f('s.docx'), content: [[heading: 'Intro', body: 'x']]])
        XWPFDocument doc = new File(f('s.docx')).withInputStream { new XWPFDocument(it) }

        then:
        doc.styles.styleExist('Heading1')
        doc.paragraphs[0].style == 'Heading1'

        cleanup:
        doc?.close()
    }

    def "docx APPEND_SECTION adds sections at the end, in order, creating the file when absent"() {
        when:
        OfficeTools.docx().call('APPEND_SECTION', [filePath: f('ap.docx'), content: [[heading: 'One', body: '1']]])
        Map r = OfficeTools.docx().call('APPEND_SECTION', [filePath: f('ap.docx'), content: [['Two', '2'], ['Three', ['x']]]]) as Map
        Map read = OfficeTools.docx().call('READ', [filePath: f('ap.docx')]) as Map

        then:
        r.sectionsAppended == 2
        read.sections*.heading == ['One', 'Two', 'Three']
        read.sections*.content == ['1', '2', '\u2022 x']
    }

    def "docx REPLACE_SECTION replaces one section's body in place -- middle or last, text or list"() {
        given:
        OfficeTools.docx().call('GENERATE', [filePath: f('r.docx'), content: [['Intro', 'hello'], ['Plan', 'old'], ['Close', 'bye']]])

        when:
        Map r = OfficeTools.docx().call('REPLACE_SECTION', [filePath: f('r.docx'), targetSection: target, content: body]) as Map
        Map read = OfficeTools.docx().call('READ', [filePath: f('r.docx')]) as Map

        then:
        r.replacedSection == target
        read.sections*.heading == ['Intro', 'Plan', 'Close']
        read.sections*.content == expected

        where:
        target  | body                               || expected
        'Plan'  | 'new plan'                         || ['hello', 'new plan', 'bye']
        'Plan'  | ['p', 'q']                         || ['hello', '\u2022 p\n\u2022 q', 'bye']
        'Close' | 'farewell'                         || ['hello', 'old', 'farewell']
        'Plan'  | [[heading: 'Plan', body: 'fromlist']] || ['hello', 'fromlist', 'bye']
    }

    def "docx REPLACE_SECTION of a heading that is not there is refused and the file is unchanged"() {
        given:
        OfficeTools.docx().call('GENERATE', [filePath: f('n.docx'), content: [['Intro', 'hello']]])
        long before = new File(f('n.docx')).lastModified()

        when:
        OfficeTools.docx().call('REPLACE_SECTION', [filePath: f('n.docx'), targetSection: 'Nope', content: 'x'])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("'Nope'")
        new File(f('n.docx')).lastModified() == before
    }

    def "docx refuses unknown fields by name: top-level, in a section, and in a documentPlan map"() {
        when:
        OfficeTools.docx().call('GENERATE', config)

        then:
        IllegalArgumentException e = thrown()
        e.message.contains(named)

        where:
        config                                                                                    || named
        [filePath: 'x.docx', content: [['A', 'b']], colour: 'red']                                || "'colour'"
        [filePath: 'x.docx', content: [[heading: 'A', body: 'b', level: 2]]]                      || 'level'
        [filePath: 'x.docx', documentPlan: [sections: [[heading: 'A', text: 'b']], bogusField: 1]] || "'bogusField'"
    }

    def "docx documentPlan as a plain map is bound and generated"() {
        when:
        OfficeTools.docx().call('GENERATE', [filePath: f('plan.docx'), documentPlan: [sections: [[heading: 'Intro', text: 'hello']]]])
        Map read = OfficeTools.docx().call('READ', [filePath: f('plan.docx')]) as Map

        then:
        read.sections == [[heading: 'Intro', content: 'hello']]
    }

    // ------------------------------------------------------------------ xlsx

    def "xlsx WRITE overwrites one sheet of an existing workbook and leaves the others"() {
        given:
        OfficeTools.xlsx().call('WRITE', [filePath: f('w.xlsx'), sheet: 'Keep', headers: ['k'], rows: [['kept']]])
        OfficeTools.xlsx().call('WRITE', [filePath: f('w.xlsx'), sheetName: 'S', headers: ['k'], rows: [['one']]])

        when:
        Map w = OfficeTools.xlsx().call('WRITE', [filePath: f('w.xlsx'), sheet: 'S', headers: ['k'], rows: [['two'], ['three']]]) as Map
        Map s = OfficeTools.xlsx().call('READ', [filePath: f('w.xlsx'), sheet: 'S']) as Map
        Map k = OfficeTools.xlsx().call('READ', [filePath: f('w.xlsx'), sheetName: 'Keep']) as Map

        then:
        w.rowsWritten == 2
        s.data == [[k: 'two'], [k: 'three']]
        s.rowCount == 2
        k.data == [[k: 'kept']]
    }

    def "xlsx APPEND of map rows follows the sheet's header row, not the order the keys arrived in"() {
        given:
        OfficeTools.xlsx().call('WRITE', [filePath: f('ap.xlsx'), sheet: 'S', headers: ['name', 'age'], rows: [['ann', 30]]])

        when:
        OfficeTools.xlsx().call('APPEND', [filePath: f('ap.xlsx'), sheet: 'S', rows: [sorted([name: 'bob', age: 40])]])
        Map s = OfficeTools.xlsx().call('READ', [filePath: f('ap.xlsx'), sheet: 'S']) as Map

        then:
        s.data == [[name: 'ann', age: 30d], [name: 'bob', age: 40d]]
    }

    def "xlsx APPEND of a map row with a column the sheet does not have is refused by name"() {
        given:
        OfficeTools.xlsx().call('WRITE', [filePath: f('bad.xlsx'), sheet: 'S', headers: ['name'], rows: [['ann']]])

        when:
        OfficeTools.xlsx().call('APPEND', [filePath: f('bad.xlsx'), sheet: 'S', rows: [[name: 'bob', shoe: 9]]])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("'shoe'")
    }

    def "xlsx READ formats and range"() {
        given:
        OfficeTools.xlsx().call('WRITE', [filePath: f('r.xlsx'), sheet: 'S', headers: ['id', 'v'], rows: [['a', 1], ['b', 2]]])

        expect:
        OfficeTools.xlsx().call('READ', [filePath: f('r.xlsx'), outputFormat: 'map_by_key', keyColumn: 'id']).data.b.v == 2d
        OfficeTools.xlsx().call('READ', [filePath: f('r.xlsx'), readFormat: 'list_of_lists']).data[0] == ['id', 'v']
        OfficeTools.xlsx().call('READ', [filePath: f('r.xlsx'), range: 'A1:A3']).headers == ['id']
    }

    def "xlsx refuses an unknown field by name"() {
        when:
        OfficeTools.xlsx().call('WRITE', [filePath: f('u.xlsx'), sheet: 'S', rows: [['a']], colour: 'red'])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains("'colour'")
    }

    def "xlsx workbookPlan as a plain map is bound and goes to the adapter"() {
        when:
        OfficeTools.xlsx().call('GENERATE', [filePath: f('p.xlsx'), workbookPlan: [sheets: [[name: 'P', rows: [[cells: [[value: 'x']]]]]]]])
        XSSFWorkbook wb = new File(f('p.xlsx')).withInputStream { new XSSFWorkbook(it) }

        then:
        wb.getSheet('P').getRow(0).getCell(0).stringCellValue == 'x'

        cleanup:
        wb?.close()
    }

    // ------------------------------------------------------------------ pptx

    def "pptx GENERATE with Title Only + notes and Title and Content, read back in the tool shape"() {
        when:
        OfficeTools.pptx().call('GENERATE', [filePath: f('p.pptx'), slides: [
            sorted([layout: 'Title Only', title: 'One', notes: 'n1']),
            sorted([layout: 'Title and Content', title: 'Two', content: 'c2', notes: 'n2'])]])
        Map read = OfficeTools.pptx().call('READ', [filePath: f('p.pptx')]) as Map

        then:
        read.slideCount == 2
        read.slides == [[slideIndex: 0, title: 'One', content: '', notes: 'n1'],
                        [slideIndex: 1, title: 'Two', content: 'c2', notes: 'n2']]
    }

    def "pptx content on a Title Only slide, an unknown layout or an unknown slide field is refused and nothing is written"() {
        when:
        OfficeTools.pptx().call('GENERATE', [filePath: f('bad.pptx'), slides: [slide]])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains(named)
        !new File(f('bad.pptx')).exists()

        where:
        slide                                                   || named
        [layout: 'Title Only', title: 'T', content: 'c']        || 'no body placeholder'
        [layout: 'No Such Layout', title: 'T']                  || 'No Such Layout'
        [layout: 'Title Only', title: 'T', subtitle: 's']       || "'subtitle'"
    }

    def "pptx ADD_SLIDE appends to an existing deck"() {
        given:
        OfficeTools.pptx().call('GENERATE', [filePath: f('a.pptx'), slides: [[title: 'One']]])

        when:
        Map r = OfficeTools.pptx().call('ADD_SLIDE', [filePath: f('a.pptx'), slides: [[title: 'Two', content: 'c']]]) as Map

        then:
        r.totalSlides == 2
        OfficeTools.pptx().call('READ', [filePath: f('a.pptx')]).slides*.title == ['One', 'Two']
    }

    // ------------------------------------------------------------------ binder paths and atomic save

    def "a binder with a path rule refuses a templatePath outside it"() {
        given:
        OfficePlanBinder binder = new OfficePlanBinder({ String p -> p.startsWith(dir.toString()) } as java.util.function.Predicate<String>, { String p -> p })

        when:
        OfficeTools.docx(binder).call('GENERATE', [filePath: f('t.docx'), templatePath: 'C:/elsewhere/t.docx', content: [['A', 'b']]])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('templatePath')
    }

    def "an atomic save waits out a handle another process holds briefly on the target (Windows sharing violation)"() {
        given:
        File target = new File(f('held.bin'))
        target.bytes = [1] as byte[]
        RandomAccessFile held = new RandomAccessFile(target, 'r')
        Thread.start { sleep 300; held.close() }

        when:
        OfficeFiles.writeAtomically(target) { OutputStream out -> out.write([2] as byte[]) }

        then:
        target.bytes == [2] as byte[]
    }
}
