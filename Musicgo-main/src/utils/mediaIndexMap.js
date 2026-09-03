export function createMediaIndexMap(mediaItems) {
    const indexMap = {};
    mediaItems.forEach((item, index) => {
        // 映射中不存在
        if (!indexMap[item.platform]) {
            indexMap[item.platform] = {
                [item.id]: index,
            };
        }
        else {
            // 修改映射
            indexMap[item.platform][item.id] = index;
        }
    });
    function getIndexMap() {
        return indexMap;
    }
    function getIndex(mediaItem) {
        if (!mediaItem) {
            return -1;
        }
        return indexMap[mediaItem.platform]?.[mediaItem.id] ?? -1;
    }
    function has(mediaItem) {
        if (!mediaItem) {
            return false;
        }
        return indexMap[mediaItem.platform]?.[mediaItem.id] > -1;
    }
    return {
        getIndexMap,
        getIndex,
        has,
    };
}
