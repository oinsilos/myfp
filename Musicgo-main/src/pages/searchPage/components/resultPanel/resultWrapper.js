import ListEmpty from "@/components/base/listEmpty";
import ListFooter from "@/components/base/listFooter";
import Loading from "@/components/base/loading";
import { RequestStateCode } from "@/constants/commonConst";
import useOrientation from "@/hooks/useOrientation";
import rpx from "@/utils/rpx";
import { FlashList } from "@shopify/flash-list";
import { useAtomValue } from "jotai";
import React, { memo, useCallback, useEffect, useState } from "react";
import useSearch from "../../hooks/useSearch";
import { queryAtom } from "../../store/atoms";
import { renderMap } from "./results";
function ResultWrapper(props) {
    const { tab, pluginHash, searchResult, pluginSearchResultRef } = props;
    const search = useSearch();
    const [searchState, setSearchState] = useState(searchResult?.state ?? RequestStateCode.IDLE);
    const orientation = useOrientation();
    const query = useAtomValue(queryAtom);
    const ResultComponent = renderMap[tab];
    const data = searchResult?.data ?? [];
    const keyExtractor = useCallback((item, i) => `${i}-${item.platform}-${item.id}`, []);
    useEffect(() => {
        if (searchState === RequestStateCode.IDLE) {
            search(query, 1, tab, pluginHash);
        }
    }, []);
    useEffect(() => {
        setSearchState(searchResult?.state ?? RequestStateCode.IDLE);
    }, [searchResult]);
    const renderItem = ({ item, index }) => (<ResultComponent item={item} index={index} pluginHash={pluginHash} pluginSearchResultRef={pluginSearchResultRef}/>);
    return searchState === RequestStateCode.PENDING_FIRST_PAGE ? (<Loading />) : (<FlashList extraData={searchState} ListEmptyComponent={<ListEmpty state={searchState} onRetry={() => {
                search(query, 1, tab, pluginHash);
            }}/>} ListFooterComponent={data?.length ? <ListFooter state={searchState} onRetry={() => {
                search(query, undefined, tab, pluginHash);
            }}/> : null} data={data} refreshing={false} onRefresh={() => {
            search(query, 1, tab, pluginHash);
        }} onEndReached={() => {
            (searchState === RequestStateCode.PARTLY_DONE ||
                searchState === RequestStateCode.IDLE) &&
                search(undefined, undefined, tab, pluginHash);
        }} estimatedItemSize={tab === "sheet" ? rpx(306) : rpx(120)} numColumns={tab === "sheet" ? (orientation === "vertical" ? 3 : 4) : 1} renderItem={renderItem} keyExtractor={keyExtractor}/>);
}
export default memo(ResultWrapper);
