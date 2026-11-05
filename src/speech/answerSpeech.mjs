import api from '../apis/module/answerSpeech';
import {createAnswerSpeechCoordinator} from './answerSpeechCoordinator.mjs';
export default createAnswerSpeechCoordinator({client:api});
