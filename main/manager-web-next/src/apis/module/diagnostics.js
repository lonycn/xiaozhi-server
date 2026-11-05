import store from '../../store';
import {getServiceUrl} from '../serviceUrl';
import {createDiagnosticsClient} from './diagnosticsClient.mjs';
export default createDiagnosticsClient({baseUrl:getServiceUrl()||'/xiaozhi',getToken:()=>{try{return JSON.parse(store.getters.getToken||'{}').token||'';}catch{return '';}},onUnauthorized:()=>{store.commit('clearAuth');window.location.hash='/login';}});
