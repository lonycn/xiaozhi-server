import store from '../../store';
import { getServiceUrl } from '../serviceUrl';
import { createAnswerSpeechClient } from './answerSpeechClient.mjs';
const token=()=>{try{return JSON.parse(store.getters.getToken||'{}').token||'';}catch{return '';}};
export default createAnswerSpeechClient({baseUrl:getServiceUrl()||'/xiaozhi',getToken:token,onUnauthorized:()=>{store.commit('clearAuth');window.location.hash='/login';}});
