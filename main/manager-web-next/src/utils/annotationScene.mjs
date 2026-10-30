export const ANNOTATION_TOOLS = ['arrow', 'circle', 'rect', 'text', 'freehand'];
export const cloneScene = scene => JSON.parse(JSON.stringify(scene));
export const emptyScene = frame => ({ format: 'annotation-v1', frame_id: String(frame.id), width: frame.width, height: frame.height, elements: [] });
const fail = () => { throw new Error('INVALID_ANNOTATION_SCENE'); };
function exact(value, keys) { if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).sort().join(',') !== [...keys].sort().join(',')) fail(); }
function bounded(value, min = 0, max = 1) { if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) fail(); }
function point(value) { exact(value, ['x','y']); bounded(value.x); bounded(value.y); }
export function validateAnnotationScene(scene, frame) {
  exact(scene, ['format','frame_id','width','height','elements']);
  if (scene.format !== 'annotation-v1' || typeof scene.frame_id !== 'string' || !/^[1-9][0-9]*$/.test(scene.frame_id) || !Number.isInteger(scene.width) || !Number.isInteger(scene.height) || scene.width < 1 || scene.height < 1 || scene.width > 8192 || scene.height > 8192 || scene.width * scene.height > 24000000) fail();
  if (frame && (String(frame.id) !== scene.frame_id || frame.width !== scene.width || frame.height !== scene.height)) fail();
  if (!Array.isArray(scene.elements) || scene.elements.length > 200) fail();
  const ids = new Set(), min = Math.min(scene.width, scene.height); let points = 0, textLength = 0;
  for (const e of scene.elements) {
    if (!e || typeof e !== 'object' || typeof e.id !== 'string' || typeof e.color !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(e.id) || ids.has(e.id) || !/^#[0-9a-fA-F]{6}$/.test(e.color)) fail(); ids.add(e.id);
    const common = ['id','type','color','stroke_width'];
    if (e.type === 'text') {
      exact(e, ['id','type','color','x','y','text','font_size']); bounded(e.x); bounded(e.y); bounded(e.font_size, .005, .1);
      if (typeof e.text !== 'string' || !e.text.trim() || Array.from(e.text).length > 1000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\ud800-\udfff]/u.test(e.text)) fail();
      textLength += Array.from(e.text).length;
    } else {
      bounded(e.stroke_width, .001, .02);
      if (e.type === 'arrow' || e.type === 'freehand') {
        exact(e, [...common,'points']); if (!Array.isArray(e.points) || e.points.length < 2 || e.points.length > (e.type === 'arrow' ? 2 : 2000)) fail();
        e.points.forEach(point); points += e.points.length;
      } else if (e.type === 'rect') {
        exact(e, [...common,'x','y','width','height']); bounded(e.x); bounded(e.y); bounded(e.width, Number.EPSILON); bounded(e.height, Number.EPSILON);
        if (e.x + e.width > 1 + 1e-12 || e.y + e.height > 1 + 1e-12) fail();
      } else if (e.type === 'circle') {
        exact(e, [...common,'cx','cy','radius']); bounded(e.cx); bounded(e.cy); bounded(e.radius, Number.EPSILON);
        const r = e.radius * min, x = e.cx * scene.width, y = e.cy * scene.height;
        if (r > Math.min(x, y, scene.width - x, scene.height - y) + 1e-9) fail();
      } else fail();
    }
  }
  if (points > 20000 || textLength > 8000 || new TextEncoder().encode(JSON.stringify(scene)).byteLength > 256 * 1024) fail();
  return scene;
}
export function normalizedCanvasPoint(clientX, clientY, rect) {
  if (!(rect.width > 0 && rect.height > 0)) return null;
  return { x: Math.min(1, Math.max(0, (clientX - rect.left) / rect.width)), y: Math.min(1, Math.max(0, (clientY - rect.top) / rect.height)) };
}
export function annotationShape(tool, start, end, scene, style, id) {
  const e = { id, type: tool, color: style.color, stroke_width: style.stroke_width };
  if (tool === 'arrow') return { ...e, points: [start, end] };
  if (tool === 'rect') return { ...e, x: Math.min(start.x,end.x), y: Math.min(start.y,end.y), width: Math.abs(end.x-start.x), height: Math.abs(end.y-start.y) };
  if (tool === 'circle') {
    const min = Math.min(scene.width,scene.height), x = start.x * scene.width, y = start.y * scene.height;
    const r = Math.min(Math.hypot((end.x-start.x)*scene.width,(end.y-start.y)*scene.height), x, y, scene.width-x, scene.height-y);
    return { ...e, cx: start.x, cy: start.y, radius: r / min * (1-1e-12) };
  }
  return { ...e, points: [start, end] };
}
export function createAnnotationHistory(scene) {
  let values = [cloneScene(scene)], index = 0;
  return {
    get current() { return cloneScene(values[index]); }, get canUndo() { return index > 0; }, get canRedo() { return index < values.length - 1; },
    commit(next) { if (JSON.stringify(next) === JSON.stringify(values[index])) return this.current; values = values.slice(0,index+1); values.push(cloneScene(next)); if (values.length > 101) values.shift(); index = values.length-1; return this.current; },
    undo() { if (index > 0) index--; return this.current; }, redo() { if (index < values.length-1) index++; return this.current; },
    reset() { return this.commit({ ...this.current, elements: [] }); },
  };
}
export function drawAnnotationScene(ctx, scene, background, transient) {
  const W = scene.width, H = scene.height, min = Math.min(W,H);
  ctx.clearRect(0,0,W,H); if (background) ctx.drawImage(background,0,0,W,H);
  for (const e of [...scene.elements, ...(transient ? [transient] : [])]) {
    ctx.save(); ctx.strokeStyle = e.color; ctx.fillStyle = e.color; ctx.lineWidth = (e.stroke_width || .003)*min; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.beginPath();
    if (e.type === 'rect') ctx.strokeRect(e.x*W,e.y*H,e.width*W,e.height*H);
    else if (e.type === 'circle') { ctx.arc(e.cx*W,e.cy*H,e.radius*min,0,Math.PI*2); ctx.stroke(); }
    else if (e.type === 'text') { const font = e.font_size*min; ctx.font = `${font}px sans-serif`; ctx.textBaseline = 'top'; e.text.split('\n').forEach((line,index) => ctx.fillText(line,e.x*W,e.y*H+index*font*1.25)); }
    else { e.points.forEach((p,i) => { if (i) ctx.lineTo(p.x*W,p.y*H); else ctx.moveTo(p.x*W,p.y*H); }); ctx.stroke();
      if (e.type === 'arrow') { const [a,b] = e.points, angle = Math.atan2((b.y-a.y)*H,(b.x-a.x)*W), length = Math.max(ctx.lineWidth*4,min*.012); ctx.beginPath(); ctx.moveTo(b.x*W,b.y*H); ctx.lineTo(b.x*W-length*Math.cos(angle-.45),b.y*H-length*Math.sin(angle-.45)); ctx.moveTo(b.x*W,b.y*H); ctx.lineTo(b.x*W-length*Math.cos(angle+.45),b.y*H-length*Math.sin(angle+.45)); ctx.stroke(); }
    }
    ctx.restore();
  }
}
export function annotationErrorKey(error) {
  if (error.status === 401 || error.status === 403) return 'annotations.denied';
  if (error.status === 404) return 'annotations.unavailable';
  if (error.code === 'TIMEOUT') return 'annotations.timeout';
  if (error.message === 'INVALID_ANNOTATION_SCENE') return 'annotations.invalidScene';
  return 'annotations.failed';
}
