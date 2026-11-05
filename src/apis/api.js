// 引入各个模块的请求
import admin from './module/admin.js'
import agent from './module/agent.js'
import device from './module/device.js'
import dict from './module/dict.js'
import model from './module/model.js'
import ota from './module/ota.js'
import timbre from "./module/timbre.js"
import user from './module/user.js'
import voiceClone from './module/voiceClone.js'
import voiceResource from './module/voiceResource.js'
import knowledgeBase from './module/knowledgeBase.js'
import correctWord from './module/correctWord.js'
import addressBook from './module/addressBook.js'



import { getServiceUrl } from './serviceUrl';
export { getServiceUrl } from './serviceUrl';

/** request服务封装 */
export default {
    getServiceUrl,
    user,
    admin,
    agent,
    device,
    model,
    timbre,
    ota,
    dict,
    voiceResource,
    voiceClone,
    knowledgeBase,
    correctWord,
    addressBook
  }
