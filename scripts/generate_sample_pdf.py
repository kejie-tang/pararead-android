#!/usr/bin/env python3
"""Build the original Apache-2.0 reading sample. Requires reportlab 4.4.9."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, Frame, Table, TableStyle, Spacer
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.colors import HexColor, white

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'samples/pararead-reading-guide.pdf'
INK = HexColor('#142b35')
TEAL = HexColor('#147d83')
BODY = ParagraphStyle('body', fontName='Times-Roman', fontSize=11, leading=15, textColor=INK, spaceAfter=11)
HEADING = ParagraphStyle('heading', fontName='Helvetica-Bold', fontSize=12, leading=16, textColor=TEAL, spaceBefore=9, spaceAfter=9)
SMALL = ParagraphStyle('small', parent=BODY, fontSize=9, leading=12)
# Original instructional prose, not a research paper or a reported experiment.
PAGES = [
('Reading across languages', 'An original sample for ParaRead Android', [
('1. A small reading experiment', [
'This guide is an original demonstration document. It explains a few ideas about reading, translation, and local software. It does not reproduce a published research paper, and it reports no scientific results.',
'A reader often moves between a source sentence and a translation. Keeping both passages visible reduces the effort of finding the same idea twice. A useful reading tool preserves the connection even when the two languages occupy different amounts of space.',
'Try selecting a sentence on the left. Then select its translation on the right. The highlighted region should help you identify the corresponding passage. A visual connection supports careful reading, but it cannot guarantee that the translation is correct.']),
('2. Meaning before fluency', [
'A fluent sentence may still change the meaning of the source. Compare names, quantities, negation, and conditions before accepting an unfamiliar claim. The phrase "may improve" expresses uncertainty; it should not become "always improves" in a translation.',
'Context also matters. The word "model" can describe a mathematical object, a software artifact, or a simplified explanation. A glossary can keep terminology consistent, while the surrounding paragraph determines which sense is appropriate.'])], [
('3. What the sample contains', [
'The following pages include two columns, a small table, a vector diagram, and a simple formula. They provide varied material for trying sentence alignment, page navigation, translation caching, and PDF annotations.',
'The sample is intentionally small. It is not a benchmark for very large books, damaged documents, scanned pages, or sustained model inference. Those cases need separate files and measurements.']),
('4. Start with one page', [
'Begin with the first page and translate a short passage. Read the source again after reading the translation. Ask whether the same actors, actions, and limitations remain visible.',
'If no local model is installed, the explicit mock mode can demonstrate interaction. Mock output is a software fixture rather than a translation. To evaluate language quality, install a supported model or choose a configured translation service.']),
('Try it', [
'Navigate to page 2, return here, and select another sentence. Add a short note in the margin, then save a copy. Your imported original remains a separate document.'])]),
('Local translation and privacy', 'Understand where the text goes', [
('5. A local workflow', [
'A local language model runs on the device after its weights have been downloaded. The application prepares a prompt, sends a batch to its local inference engine, and receives generated text. The model file and the reading document serve different purposes.',
'Offline inference avoids sending the prompt to a remote translation endpoint. Initial downloads still require a network connection. Local storage also needs enough free space for the document snapshot, the model, and any temporary download files.',
'Memory, processor speed, and thermal limits affect the experience. A short successful translation does not establish the speed of a complete book. Measure sustained work on the tablet that will actually be used.']),
('6. Choosing a service', [
'An optional remote API can provide another translation backend. When it is selected, the configured service receives the submitted text and prompt. The reader should make that choice deliberately and understand the service being used.',
'Keep API credentials out of public issue reports and screenshots. A useful bug report can describe the selected backend, the visible error, and the steps to reproduce the problem without including a secret.'])], [
('7. Separate responsibilities', [
'The PDF renderer displays pages. The segmenter groups extracted text into sentences. The translator generates target-language text. The cache stores reusable results. A failure in one stage may leave the other stages working.',
'For example, a scanned page may contain only an image. It can render perfectly while providing no selectable text. This is different from a text page whose translation request failed.']),
('8. Read the status carefully', [
'Progress is an observation about completed work, not a quality score. A page with translated sentences can still contain a mistaken term. A completed job should therefore remain open to human review.',
'Cancellation stops further work as the engine reaches an appropriate boundary. Previously committed translations can remain useful. Reopening a document should preserve durable progress when the document and translation settings still match.'])]),
('Alignment, layout, and navigation', 'Follow the same idea in two places', [
('9. Coordinates and sentences', [
'A PDF page describes visual positions rather than a simple sequence of paragraphs. Text can be split into fragments, arranged in columns, or interrupted by a caption. The reading order must be inferred from these fragments.',
'Long translations often occupy more lines than their source sentences. Equal font sizes do not guarantee equal block heights. Sentence alignment therefore connects passages by identity and page position, rather than by line number alone.']),
('10. A simple formula', [
'Let n be the number of pages in a document and k the number whose processing has completed. A basic progress fraction is p = k / n, with 0 &lt;= k &lt;= n. This formula describes processing progress only.',
'If n = 6 and k = 3, the fraction is 0.5. This example does not measure accuracy, memory use, or the amount of text translated. Different pages can contain very different amounts of work.'])], [
('11. A reading loop', [
'Use the diagram below as a navigation exercise. Each box describes an action in a reading session. The arrows express a suggested order, not an automatic application behavior.']),
('diagram', []),
('12. Keep your place', [
'A useful reader remembers the document and a meaningful position within it. Rotation, zoom changes, or a wider translation panel can change the visible layout. The saved position should still lead back to the same passage.',
'Try rotating the tablet while this page is open. Check whether both columns remain readable and whether selecting a sentence still reaches the corresponding translation.'])]),
('Inspecting results', 'A table is a prompt for comparison', [
('13. An illustrative table', [
'The values below are invented examples for testing table extraction. They are not measurements from a model, device, or user study. Do not use them to compare products or make performance claims.']),
('table', []),
('14. Compare like with like', [
'A meaningful timing report states the hardware, model, quantization, prompt size, and test conditions. It distinguishes an initial load from repeated inference. It also records failed and cancelled requests.',
'Table structure can be difficult to translate sentence by sentence. Row labels and units give numbers their meaning. When extracted text is ambiguous, inspect the original page rather than inferring a new relationship.'])], [
('15. A practical review', [
'Check the smallest details first: a missing minus sign, a changed unit, or a reversed condition can alter the conclusion. Read figure captions and footnotes together with the paragraph that refers to them.',
'Then compare the logical structure. Does a proposed explanation remain a proposal? Does a limitation remain attached to the result it qualifies? Does an example remain clearly distinguishable from a measured observation?']),
('16. Record uncertainty', [
'An annotation can capture a question without editing the author\'s words. A short note such as "verify the unit" is often more useful than silently replacing a phrase in your own understanding.',
'Save a separate annotated copy when you want to share comments. Check that the exported file reopens and that the annotation appears on the intended page.'])]),
('Caching and recovery', 'Continue from work that was saved', [
('17. Reuse with care', [
'A translation cache can avoid repeating a model request for unchanged source text. Its identity also needs to account for the selected model, target language, glossary, and prompt rules. Otherwise an old answer may appear under new settings.',
'Whitespace changes can sometimes be normalized without changing meaning. A different sentence or target language must remain distinct. Cache design is therefore about preserving the right distinctions, as well as improving speed.']),
('18. A durable checkpoint', [
'A checkpoint records how much work can safely be considered complete. Saving the next-page cursor before saving the page translations would create a gap after interruption. Saving translations first allows a resumed job to reuse committed sentences.',
'A reader restart and a device reboot are different recovery conditions. Testing one does not prove the other. Reports should name the interruption that was actually exercised.'])], [
('19. Deliberate cancellation', [
'When a reader cancels a whole-document job, reopening the document should respect that choice. A separate continue action can resume the task. A stale event from an earlier job should not advance a newer one.',
'Changing the translation configuration can invalidate a saved cursor while leaving unrelated reading state intact. The application should associate progress with the correct document revision and configuration.']),
('20. Try a short session', [
'Translate more than one page, cancel the task, and reopen the same document. Observe the stored status. Then continue and check whether previously saved passages are reused.',
'This exercise uses a tiny sample and your selected backend. It cannot establish reliability for long-running jobs, power loss, or background process termination.'])]),
('A careful reading checklist', 'Use the tool to support your own judgement', [
('21. Before you begin', [
'Choose a document that you have permission to use. Identify whether it contains selectable text or scanned images. Confirm the selected translation backend and the expected network behavior.',
'If you use a local model, verify that it is ready before starting a long task. Keep enough storage for the document and its annotated exports. Begin with a short passage to check terminology.']),
('22. While you read', [
'Compare the source and translation whenever the wording affects your understanding. Follow references back to their surrounding paragraph. Treat generated text as a reading aid that can require correction.',
'Use a glossary for recurring technical terms and annotations for unresolved questions. Navigate between pages to check that a sentence has not been separated from an essential condition.'])], [
('23. When you finish', [
'Export annotations to a new PDF and reopen that copy. Check page count, annotation placement, and any important figures. Keep the source citation with any notes that you share.',
'If the application fails, record the backend, page, operation, and visible error. A small authorized reproduction file is easier to investigate than an entire private document.']),
('24. About this document', [
'This original instructional sample was prepared for ParaRead Android in 2026. The text, tables, and vector artwork are project-created demonstration content. They are licensed under the Apache License, Version 2.0, together with the accompanying sample notice.',
'This guide is not Attention Is All You Need, an academic publication, or an evaluation report. For the Transformer paper by Vaswani and colleagues, follow the official arXiv link in the project README and import your own downloaded copy.'])]),
]

def flowables(sections):
    result=[]
    for title, paras in sections:
        if title == 'diagram':
            result.append(Spacer(1,150)); continue
        if title == 'table':
            table=Table([['Fixture', 'Pages', 'Status'], ['Small guide', '6', 'Illustrative'], ['Short excerpt', '2', 'Illustrative'], ['Reading note', '1', 'Illustrative']], colWidths=[92,42,96], rowHeights=28)
            table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),TEAL),('TEXTCOLOR',(0,0),(-1,0),white),('FONTNAME',(0,0),(-1,0),'Helvetica-Bold'),('FONTNAME',(0,1),(-1,-1),'Helvetica'),('FONTSIZE',(0,0),(-1,-1),9),('TEXTCOLOR',(0,1),(-1,-1),INK),('ROWBACKGROUNDS',(0,1),(-1,-1),[HexColor('#edf5f4'),white]),('LINEBELOW',(0,0),(-1,0),.7,TEAL)]))
            result.extend([table,Spacer(1,14)]);continue
        result.append(Paragraph(title,HEADING))
        result.extend(Paragraph(p,BODY) for p in paras)
    return result

OUT.parent.mkdir(parents=True,exist_ok=True)
c=canvas.Canvas(str(OUT),pagesize=(612,792),pageCompression=1,invariant=1)
c.setTitle('ParaRead Reading Guide - Original Demonstration Sample')
c.setAuthor('ParaRead Android contributors')
c.setSubject('Original sample; Apache-2.0; not a research publication')
for number,(title,sub,left,right) in enumerate(PAGES,1):
    c.setFillColor(TEAL);c.rect(42,744,32,4,fill=1,stroke=0)
    c.setFont('Helvetica-Bold',9);c.drawString(84,742,'PARAREAD / READING GUIDE')
    c.setFillColor(INK);c.setFont('Helvetica-Bold',23);c.drawString(42,704,title)
    c.setFillColor(TEAL);c.setFont('Helvetica',11);c.drawString(42,683,sub)
    for x,sections in [(42,left),(321,right)]:
        story=flowables(sections)
        frame=Frame(x,72,249,585,leftPadding=0,rightPadding=0,topPadding=0,bottomPadding=0)
        frame.addFromList(story,c)
        if story: raise RuntimeError(f'Page {number} column {x} overflows')
    if number==3:
        for y,label in [(517,'Read the source'),(467,'Compare the translation'),(417,'Return to the context')]:
            c.setFillColor(HexColor('#edf5f4'));c.setStrokeColor(TEAL);c.roundRect(337,y,213,32,5,fill=1,stroke=1)
            c.setFillColor(INK);c.setFont('Helvetica',10);c.drawCentredString(443.5,y+12,label)
            if y>417:
                c.line(443.5,y-3,443.5,y-16);c.line(443.5,y-16,440.5,y-12);c.line(443.5,y-16,446.5,y-12)
    c.setStrokeColor(HexColor('#c6d6d8'));c.line(42,52,570,52)
    c.setFillColor(INK);c.setFont('Helvetica',8)
    c.drawString(42,38,'Original demo content / Apache-2.0 / Copyright 2026 ParaRead Android contributors')
    c.drawRightString(570,38,f'{number} / {len(PAGES)}')
    c.showPage()
c.save()
print(f'{OUT}: {len(PAGES)} pages, {OUT.stat().st_size} bytes')
