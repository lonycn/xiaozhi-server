import { describe, expect, it, vi } from 'vitest';
import { emptyScene, validateAnnotationScene, normalizedCanvasPoint, annotationShape, createAnnotationHistory, drawAnnotationScene } from '../src/utils/annotationScene.mjs';
const frame = { id: '9223372036854775806', width: 1920, height: 1080 };
const style = { color: '#E53935', stroke_width: .004 };
const shape = (type = 'arrow') => annotationShape(type,{ x:.25,y:.25 },{ x:.5,y:.5 },emptyScene(frame),style,'e1');
const scene = (...elements) => ({ ...emptyScene(frame), elements });
describe('annotation-v1 scene and normalized coordinates', () => {
  it('maps identical locations across viewport sizes and clamps pointer capture outside the canvas', () => {
    expect(normalizedCanvasPoint(110,70,{ left:10,top:20,width:200,height:100 })).toEqual({ x:.5,y:.5 });
    expect(normalizedCanvasPoint(210,120,{ left:10,top:20,width:400,height:200 })).toEqual({ x:.5,y:.5 });
    expect(normalizedCanvasPoint(-2,999,{ left:0,top:0,width:100,height:100 })).toEqual({ x:0,y:1 });
    expect(normalizedCanvasPoint(0,0,{ width:0,height:0 })).toBeNull();
  });
  it.each(['arrow','rect','circle','freehand'])('creates a valid %s on a non-square source', type => expect(validateAnnotationScene(scene(shape(type)),frame)).toBeDefined());
  it('uses a true pixel circle and keeps a dragged radius inside image bounds', () => {
    const circle = annotationShape('circle',{ x:.05,y:.5 },{ x:1,y:1 },emptyScene(frame),style,'c');
    expect(circle.radius * 1080).toBeCloseTo(.05 * 1920); expect(validateAnnotationScene(scene(circle))).toBeDefined();
  });
  it.each([
    s => { s.url='https://attacker.test'; }, s => { s.elements[0].image='data:image/svg+xml;base64,malicious'; },
    s => { s.width=100; }, s => { s.elements[0].points[0].x=NaN; }, s => { s.elements[0].points[0].y=Infinity; },
    s => { s.elements[0].points[0].x=-.01; }, s => { s.elements[0].stroke_width=.1; },
    s => { s.elements[0].color='url(https://attacker.test)'; }, s => { s.elements.push({ ...s.elements[0] }); },
  ])('rejects unknown resources, frame mismatches, invalid coordinates and duplicate IDs', mutation => {
    const value = scene(shape()); mutation(value); expect(() => validateAnnotationScene(value,frame)).toThrow('INVALID_ANNOTATION_SCENE');
  });
  it('rejects geometric bounds and point/element limits', () => {
    expect(() => validateAnnotationScene(scene({ ...shape('rect'),x:.9,width:.2 }))).toThrow();
    expect(() => validateAnnotationScene(scene({ ...shape('circle'),cx:0 }))).toThrow();
    expect(() => validateAnnotationScene(scene({ ...shape('freehand'),points:Array(2001).fill({ x:0,y:0 }) }))).toThrow();
    expect(() => validateAnnotationScene(scene(...Array.from({length:201},(_,i) => ({ ...shape(),id:`e${i}` }))))).toThrow();
    expect(() => validateAnnotationScene(scene(...Array.from({length:11},(_,i) => ({ ...shape('freehand'),id:`e${i}`,points:Array(2000).fill({ x:0,y:0 }) }))))).toThrow();
  });
  it('counts Unicode code points, permits plain CJK/emoji and rejects scripts as resources rather than executing text', () => {
    const text = { id:'text',type:'text',color:'#123456',x:.1,y:.1,font_size:.04,text:'冷库🙂\n<script>plain text</script>' };
    expect(validateAnnotationScene(scene(text))).toBeDefined(); expect(validateAnnotationScene(scene({ ...text,text:'🙂'.repeat(1000) }))).toBeDefined();
    for (const value of ['x'.repeat(1001),'\u0000','\u007f','\ud800']) expect(() => validateAnnotationScene(scene({ ...text,text:value }))).toThrow();
    expect(() => validateAnnotationScene(scene({ ...text,font_family:'remote-font' }))).toThrow();
  });
  it('keeps reset undoable, preserves redo and truncates abandoned history', () => {
    const history = createAnnotationHistory(emptyScene(frame)); history.commit(scene(shape())); history.reset(); expect(history.current.elements).toEqual([]);
    expect(history.undo().elements).toHaveLength(1); expect(history.redo().elements).toEqual([]); history.undo(); history.commit(scene(shape('rect'))); expect(history.canRedo).toBe(false);
    const copy = history.current; copy.elements.length=0; expect(history.current.elements).toHaveLength(1);
  });
  it('renders the five primitives in source pixel coordinates at any backing resolution', () => {
    const ctx = new Proxy({}, { get(target,key) { return target[key] ||= vi.fn(); } });
    const text = { id:'text',type:'text',color:'#123456',x:.1,y:.1,font_size:.04,text:'冷库\n现场' };
    const value = scene(...['arrow','rect','circle','freehand'].map((type,index) => ({...shape(type),id:`e${index}`})),text);
    drawAnnotationScene(ctx,value,{}); expect(ctx.drawImage).toHaveBeenCalledWith({},0,0,1920,1080); expect(ctx.arc).toHaveBeenCalled(); expect(ctx.strokeRect).toHaveBeenCalled(); expect(ctx.fillText.mock.calls[0]).toEqual(['冷库',192,108]); expect(ctx.font).toBe('43.2px sans-serif');
  });
});
