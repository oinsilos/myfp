import { atom } from "jotai";
/** 编辑页中的音乐条目 */
const editingMusicListAtom = atom([]);
/** 是否变动过 */
const musicListChangedAtom = atom(false);
export { editingMusicListAtom, musicListChangedAtom };
