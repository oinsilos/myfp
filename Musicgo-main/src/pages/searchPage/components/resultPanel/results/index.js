import AlbumResultItem from "./albumResultItem";
import ArtistResultItem from "./artistResultItem";
import MusicResultItem from "./musicResultItem";
import MusicSheetResultItem from "./musicSheetResultItem";
const results = [
    {
        key: "music",
        i18nKey: "common.singleMusic",
        title: "单曲",
        component: MusicResultItem,
    },
    {
        key: "album",
        i18nKey: "common.album",
        title: "专辑",
        component: AlbumResultItem,
    },
    {
        key: "artist",
        i18nKey: "common.artist",
        title: "作者",
        component: ArtistResultItem,
    },
    {
        key: "sheet",
        i18nKey: "common.sheet",
        title: "歌单",
        component: MusicSheetResultItem,
    },
];
const renderMap = {};
results.forEach(_ => (renderMap[_.key] = _.component));
export default results;
export { renderMap };
