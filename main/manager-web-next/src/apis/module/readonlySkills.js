import store from '../../store';
import {getServiceUrl} from '../serviceUrl';
import {createReadonlySkillsClient} from './readonlySkillsClient.mjs';
export default createReadonlySkillsClient({baseUrl:getServiceUrl()||'/xiaozhi',getToken:()=>{try{return JSON.parse(store.getters.getToken||'{}').token||'';}catch{return '';}},onUnauthorized:()=>{store.commit('clearAuth');window.location.hash='/login';}});
