import { atom } from "jotai";
export const scrollToTopAtom = atom(true);
export const initQueryResult = {
    music: {},
    album: {},
};
export const queryResultAtom = atom(initQueryResult);
