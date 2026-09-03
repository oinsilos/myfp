import { State } from "react-native-track-player";
/**
 * 音乐是否处于停止状态
 * @param state
 * @returns
 */
export const musicIsPaused = (state) => state !== State.Playing;
/**
 * 音乐是否处于缓冲中状态
 * @param state
 * @returns
 */
export const musicIsBuffering = (state) => state === State.Loading || state === State.Buffering;
