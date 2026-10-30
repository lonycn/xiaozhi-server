import {authenticatedFetch} from '../httpRequest';
import store from '../../store';
import {getServiceUrl} from '../serviceUrl';
import {createRealtimeClient} from './realtimeClient.mjs';
export default createRealtimeClient({baseUrl:getServiceUrl()||'/xiaozhi',fetchImpl:authenticatedFetch,getToken:()=>{try{return JSON.parse(store.getters.getToken||'{}').token;}catch{return null;}},onUnauthorized:()=>{store.commit('clearAuth');window.location.hash='/login';}});
