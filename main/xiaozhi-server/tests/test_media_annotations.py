"""B4 real local decode/render fixtures; no cloud, storage, DB or field acceptance."""
import asyncio
import copy
import hashlib
import io
import json
import shutil
from pathlib import Path
from unittest.mock import patch
import pytest
import httpx
from PIL import Image, ImageChops, ImageFont
from media_worker.annotations import Annotations
from media_worker.config import Config
from media_worker.processor import command
from media_worker.transport import MediaFailure, LeaseLost
from media_worker.worker import Worker

FONT = Path(__file__).resolve().parents[2] / 'manager-web/public/generator/static/fonts/noto_qwen.ttf'


def config(**kwargs):
    return Config(manager_url='http://manager.invalid', worker_key='private', worker_id='b4-test', **kwargs)


def source_jpeg(width=400, height=300):
    output=io.BytesIO()
    Image.new('RGB',(width,height),'white').save(output,'JPEG')
    return output.getvalue()


def scene():
    return {'format':'annotation-v1','frame_id':'101','width':400,'height':300,'elements':[
        {'id':'rect1','type':'rect','color':'#FF0000','stroke_width':.008,'x':.05,'y':.05,'width':.25,'height':.2},
        {'id':'circle1','type':'circle','color':'#00AA00','stroke_width':.008,'cx':.5,'cy':.2,'radius':.12},
        {'id':'arrow1','type':'arrow','color':'#0000FF','stroke_width':.008,'points':[{'x':.7,'y':.1},{'x':.9,'y':.3}]},
        {'id':'pen1','type':'freehand','color':'#EE6600','stroke_width':.008,'points':[{'x':.1,'y':.45},{'x':.2,'y':.6},{'x':.3,'y':.45}]},
        {'id':'text1','type':'text','color':'#111111','x':.45,'y':.6,'text':'冷库温度\n检查','font_size':.08}]}


def task(job='annotation_preview', mime='image/jpeg', width=400,height=300):
    metadata={'frame_id':'101','source_attachment_id':'100','expected_width':width,'expected_height':height}
    if job=='capture_frame': metadata.update(requested_time_ms=None if mime=='image/jpeg' else 0,capture_policy='first_at_or_after')
    else: metadata.update(annotation_id='102',scene=scene())
    return {'job_id':'103','attachment_id':'104','job_type':job,'attempt':1,'lease_token':'lease',
            'lease_seconds':90,'filename':'source.jpg' if mime=='image/jpeg' else 'source.mp4',
            'mime_type':mime,'metadata':metadata}


async def process(tmp_path, claim, data=None):
    source=tmp_path/'input.bin'
    source.write_bytes(source_jpeg() if data is None else data)
    return await Annotations(config()).process(claim,source,tmp_path)


@pytest.mark.asyncio
async def test_real_five_shapes_and_local_chinese_font_rendered(tmp_path):
    source=source_jpeg()
    metadata,artifacts=await process(tmp_path,task(),source)
    assert metadata=={'mime_type':'image/jpeg','width':400,'height':300}
    assert {kind for kind,_,_ in artifacts}=={'display','thumbnail'}
    assert (tmp_path/'input.bin').read_bytes()==source
    with Image.open(tmp_path/'display.jpg') as image:
        assert image.size==(400,300) and not image.getexif()
        # Each spatially separate tool contributes actual decoded pixels.
        for crop in [(15,10,125,80),(160,20,240,100),(270,25,365,100),(35,125,125,190),(175,175,300,250)]:
            region=image.crop(crop)
            assert ImageChops.difference(region,Image.new('RGB',region.size,'white')).getbbox()
    font=ImageFont.truetype(str(FONT),24)
    assert bytes(font.getmask('冷')) != bytes(font.getmask('\u0378'))


@pytest.mark.asyncio
async def test_image_capture_normalizes_exif_without_changing_source(tmp_path):
    output=io.BytesIO();exif=Image.Exif();exif[274]=6;exif[270]='private original'
    Image.new('RGB',(40,20),'red').save(output,'JPEG',exif=exif)
    claim=task('capture_frame',width=20,height=40)
    metadata,_=await process(tmp_path,claim,output.getvalue())
    assert metadata=={'mime_type':'image/jpeg','width':20,'height':40} and 'actual_time_ms' not in metadata
    assert (tmp_path/'input.bin').read_bytes()==output.getvalue()
    with Image.open(tmp_path/'display.jpg') as image: assert image.size==(20,40) and not image.getexif()


@pytest.mark.asyncio
@pytest.mark.parametrize('mutate', [
    lambda s:s.update(url='https://forbidden.invalid'),
    lambda s:s.update(format='svg'),
    lambda s:s.update(frame_id='999'),
    lambda s:s.update(width=401),
    lambda s:s['elements'][0].update(x=float('nan')),
    lambda s:s['elements'][0].update(x=float('inf')),
    lambda s:s['elements'][0].update(x=-.01),
    lambda s:s['elements'][0].update(width=.99),
    lambda s:s['elements'][0].update(stroke_width=.021),
    lambda s:s['elements'][0].update(stroke_width=True),
    lambda s:s['elements'][0].update(color='url(https://forbidden.invalid)'),
    lambda s:s['elements'][1].update(cx=0,radius=.1),
    lambda s:s['elements'][2].update(points=[{'x':.2,'y':.2}]),
    lambda s:s['elements'][2]['points'][0].update(z=1),
    lambda s:s['elements'][3].update(points=[{'x':.5,'y':.5}]*2001),
    lambda s:s['elements'][4].update(text='中'*1001),
    lambda s:s['elements'][4].update(x=.99),
    lambda s:s['elements'][4].update(font_size=.11),
    lambda s:s['elements'][4].update(font_url='https://forbidden.invalid/font'),
    lambda s:s['elements'][4].update(id='rect1'),
    lambda s:s['elements'][4].update(id='../path'),
    lambda s:s.update(elements=s['elements']*41),
    lambda s:s.update(elements=[{'id':str(i),'type':'text','color':'#111111','x':0,'y':0,'text':'中'*1000,'font_size':.01} for i in range(9)]),
])
async def test_scene_schema_numbers_bounds_and_limits_rejected(tmp_path,mutate):
    claim=task();mutate(claim['metadata']['scene'])
    with pytest.raises(MediaFailure,match='INVALID_MEDIA'): await process(tmp_path,claim)
    assert not (tmp_path/'display.jpg').exists()


@pytest.mark.asyncio
async def test_json_size_and_expected_dimensions_limited(tmp_path):
    claim=task()
    claim['metadata']['scene']['elements']=[{'id':str(i),'type':'freehand','color':'#123456','stroke_width':.01,
        'points':[{'x':.123456789012345,'y':.123456789012345}]*2000} for i in range(4)]
    with pytest.raises(MediaFailure,match='INVALID_MEDIA'): await process(tmp_path,claim)
    claim=task('capture_frame',width=8193,height=1)
    with pytest.raises(MediaFailure,match='INVALID_MEDIA'): await process(tmp_path,claim)


@pytest.mark.asyncio
async def test_bad_source_mismatched_size_and_untrusted_metadata_rejected(tmp_path):
    for data in [b'not a jpeg',b'\xff\xd8\xff',source_jpeg(50,50)]:
        with pytest.raises(MediaFailure,match='INVALID_MEDIA'): await process(tmp_path,task(),data)
    claim=task();claim['metadata']['storage_key']='/private/path'
    with pytest.raises(MediaFailure,match='INVALID_MEDIA'): await process(tmp_path,claim)
    claim=task('capture_frame');claim['metadata']['requested_time_ms']=0
    with pytest.raises(MediaFailure,match='INVALID_MEDIA'): await process(tmp_path,claim)


@pytest.mark.asyncio
async def test_scene_markup_is_only_literal_text_and_source_unchanged(tmp_path):
    claim=task();claim['metadata']['scene']['elements']=[{'id':'literal','type':'text','color':'#000000',
        'x':.05,'y':.05,'text':'<svg>普通文字</svg>','font_size':.04}]
    raw=source_jpeg();metadata,_=await process(tmp_path,claim,raw)
    assert metadata['width']==400 and (tmp_path/'input.bin').read_bytes()==raw


async def colored_video(directory,offset=False,rate=2):
    for index,color in enumerate(['red','green','blue','yellow']):
        Image.new('RGB',(64,48),color).save(directory/f'fixture-{index}.png')
    output=directory/'synthetic.mp4'
    args=['ffmpeg','-nostdin','-v','error','-framerate',str(rate),'-i',directory/'fixture-%d.png']
    if offset: args+=['-vf','setpts=PTS+5/TB']
    await command([*args,'-c:v','mpeg4','-bf','2','-fps_mode','vfr','-y',output])
    return output.read_bytes()


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'),reason='FFmpeg unavailable')
async def test_precise_frame_pts_selects_first_actual_frame_after_request(tmp_path):
    video=await colored_video(tmp_path)
    for requested,actual,color_channel in [(0,0,0),(1,500,1),(499,500,1),(500,500,1),(501,1000,2)]:
        directory=tmp_path/f'req-{requested}';directory.mkdir()
        claim=task('capture_frame','video/mp4',64,48);claim['metadata']['requested_time_ms']=requested
        metadata,_=await process(directory,claim,video)
        assert metadata['actual_time_ms']==actual and metadata['width']==64 and metadata['height']==48
        with Image.open(directory/'display.jpg') as image:
            pixel=image.getpixel((32,24))
            assert pixel[color_channel]>max(pixel[i] for i in range(3) if i!=color_channel)+40
    directory=tmp_path/'no-frame';directory.mkdir()
    claim['metadata']['requested_time_ms']=1501
    with pytest.raises(MediaFailure,match='INVALID_MEDIA'): await process(directory,claim,video)
    assert (directory/'input.bin').read_bytes()==video


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'),reason='FFmpeg unavailable')
async def test_video_origin_is_normalized_before_pts_selection(tmp_path):
    video=await colored_video(tmp_path,offset=True)
    directory=tmp_path/'normalized';directory.mkdir()
    claim=task('capture_frame','video/mp4',64,48);claim['metadata']['requested_time_ms']=1
    metadata,_=await process(directory,claim,video)
    assert metadata['actual_time_ms']==500
    with Image.open(directory/'display.jpg') as image:
        red,green,blue=image.getpixel((32,24));assert green>red+40 and green>blue+40


@pytest.mark.asyncio
async def test_b4_render_revocation_cancels_work_and_cleans_owned_temp(tmp_path):
    canceled=asyncio.Event();events=[]
    class Manager:
        async def download(self,claim,path): path.write_bytes(source_jpeg())
        async def callback(self,claim,action,**body):
            events.append(action)
            if action=='heartbeat': raise LeaseLost()
        async def upload(self,*args): pytest.fail('revoked work must not upload')
    async def blocking_command(*args,**kwargs):
        try: await asyncio.Event().wait()
        except asyncio.CancelledError: canceled.set();raise
    conf=config(temp_root=str(tmp_path));conf.heartbeat_seconds=.01
    with patch('media_worker.annotations.command',blocking_command):
        with pytest.raises(LeaseLost): await Worker(conf,Manager(),None,None).execute(task())
    assert canceled.is_set() and list(tmp_path.iterdir())==[] and events==['heartbeat']


@pytest.mark.asyncio
async def test_worker_b4_branch_only_persists_two_jpeg_derivatives(tmp_path):
    raw=source_jpeg();events=[]
    class Manager:
        async def download(self,claim,path): path.write_bytes(raw)
        async def upload(self,claim,kind,mime,path):
            assert kind in ('display','thumbnail') and mime=='image/jpeg' and path.is_file()
            events.append(kind)
        async def callback(self,claim,action,**body):
            assert action=='complete' and set(body['metadata'])=={'mime_type','width','height'}
            events.append(action)
    await Worker(config(temp_root=str(tmp_path)),Manager(),None,None).execute(task())
    assert events==['display','thumbnail','complete'] and list(tmp_path.iterdir())==[]


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'),reason='FFmpeg unavailable')
async def test_fractional_pts_selection_uses_exact_time_not_rounded_display_ms(tmp_path):
    video=await colored_video(tmp_path,rate=3)
    for requested,actual,channel in [(333,334,1),(334,667,2)]:
        directory=tmp_path/f'fraction-{requested}';directory.mkdir()
        claim=task('capture_frame','video/mp4',64,48);claim['metadata']['requested_time_ms']=requested
        metadata,_=await process(directory,claim,video)
        assert metadata['actual_time_ms']==actual
        with Image.open(directory/'display.jpg') as image:
            pixel=image.getpixel((32,24))
            assert pixel[channel]>max(pixel[i] for i in range(3) if i!=channel)+40


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'),reason='FFmpeg unavailable')
async def test_capture_video_duration_limit(tmp_path):
    output=tmp_path/'too-long.mp4'
    await command(['ffmpeg','-nostdin','-v','error','-f','lavfi','-i','color=s=64x48:r=1:d=181',
                   '-c:v','mpeg4','-y',output])
    claim=task('capture_frame','video/mp4',64,48)
    with pytest.raises(MediaFailure,match='DURATION_LIMIT'): await process(tmp_path,claim,output.read_bytes())


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which('ffmpeg'),reason='FFmpeg unavailable')
async def test_java_string_capture_time_claim_download_real_pts_wire(tmp_path):
    from media_worker.transport import Manager
    video=await colored_video(tmp_path)
    wire_task=task('capture_frame','video/mp4',64,48)
    wire_task.update(size=str(len(video)),sha256=hashlib.sha256(video).hexdigest())
    wire_task['metadata'].update(expected_width='64',expected_height='48',requested_time_ms='501')
    actions=[]
    class Stream(httpx.AsyncByteStream):
        async def __aiter__(self):
            for start in range(0,len(video),65536): yield video[start:start+65536]
    async def handler(request):
        if request.url.path.endswith('/claim'):
            return httpx.Response(200,json={'code':0,'data':wire_task})
        if request.url.path.endswith('/input'):
            return httpx.Response(200,stream=Stream())
        if '/artifacts/' in request.url.path:
            with Image.open(io.BytesIO(await request.aread())) as image:
                red,green,blue=image.getpixel((32,24));assert blue>red+40 and blue>green+40
            actions.append(request.url.path.rsplit('/',1)[-1])
        if request.url.path.endswith('/complete'):
            metadata=json.loads(await request.aread())['metadata']
            assert metadata=={'mime_type':'image/jpeg','width':64,'height':48,'actual_time_ms':1000}
            actions.append('complete')
        return httpx.Response(200,json={'code':0,'data':None})
    owned=tmp_path/'worker-temp';owned.mkdir()
    conf=config(temp_root=str(owned))
    transport=httpx.AsyncClient(base_url='http://manager.invalid/',transport=httpx.MockTransport(handler))
    manager=Manager(conf,transport)
    claimed=await manager.claim()
    assert claimed['metadata']['requested_time_ms']==501 and type(claimed['metadata']['expected_width']) is int
    assert claimed['metadata']['frame_id']=='101' and wire_task['metadata']['requested_time_ms']=='501'
    await Worker(conf,manager,None,None).execute(claimed)
    assert actions==['display','thumbnail','complete'] and list(owned.iterdir())==[]
    await manager.close()
