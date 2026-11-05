import { describe, it, expect, vi } from 'vitest';
import { captureDimensions, observeVideoDimensions } from '../src/media/videoDimensions.mjs';

describe('video dimensions', () => {
  it('bounds long edges and pixels without upscaling in either orientation', () => {
    expect(captureDimensions(1080,1920)).toEqual({width:1080,height:1920});
    expect(captureDimensions(3840,2160)).toEqual({width:1920,height:1080});
    expect(captureDimensions(1000,1000)).toEqual({width:1000,height:1000});
    const square=captureDimensions(4000,4000); expect(square.width).toBe(square.height); expect(square.width*square.height).toBeLessThanOrEqual(1920*1080);
    expect(captureDimensions(10000,100)).toEqual({width:1920,height:19});
    expect(captureDimensions(0,100)).toEqual({width:0,height:0});
  });
  it('observes metadata and resize and removes listeners on replacement and disconnect', () => {
    const changed=vi.fn(), observer=observeVideoDimensions(changed), host=document.createElement('div');
    const first=document.createElement('video'), second=document.createElement('video');host.append(first);observer.add(first);
    first.dispatchEvent(new Event('loadedmetadata'));first.dispatchEvent(new Event('resize'));expect(changed).toHaveBeenCalledTimes(3);
    first.remove();host.append(second);observer.prune([host]);observer.add(second);first.dispatchEvent(new Event('resize'));expect(changed).toHaveBeenCalledTimes(4);
    second.dispatchEvent(new Event('resize'));expect(changed).toHaveBeenCalledTimes(5);observer.disconnect();second.dispatchEvent(new Event('resize'));expect(changed).toHaveBeenCalledTimes(5);
  });
});
