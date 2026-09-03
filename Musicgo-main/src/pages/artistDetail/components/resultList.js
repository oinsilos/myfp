import ListEmpty from "@/components/base/listEmpty";
import ListFooter from "@/components/base/listFooter";
import { RequestStateCode } from "@/constants/commonConst";
import { useParams } from "@/core/router";
import rpx from "@/utils/rpx";
import { FlashList } from "@shopify/flash-list";
import { useAtom } from "jotai";
import React, { useEffect, useRef, useState } from "react";
import useQueryArtist from "../hooks/useQuery";
import { scrollToTopAtom } from "../store/atoms";
const ITEM_HEIGHT = rpx(120);
export default function ResultList(props) {
    const { data, renderItem, tab } = props;
    const [scrollToTopState, setScrollToTopState] = useAtom(scrollToTopAtom);
    const lastScrollY = useRef(0);
    const { pluginHash, artistItem } = useParams();
    const [queryState, setQueryState] = useState(data?.state ?? RequestStateCode.IDLE);
    const queryArtist = useQueryArtist(pluginHash);
    useEffect(() => {
        queryState === RequestStateCode.IDLE && queryArtist(artistItem, 1, tab);
    }, []);
    useEffect(() => {
        setQueryState(data?.state ?? RequestStateCode.IDLE);
    }, [data]);
    return (<FlashList onScroll={e => {
            const currentY = e.nativeEvent.contentOffset.y;
            if (!scrollToTopState &&
                currentY < ITEM_HEIGHT * 8 - rpx(350)) {
                currentY < lastScrollY.current && setScrollToTopState(true);
            }
            else {
                if (scrollToTopState && currentY > ITEM_HEIGHT * 8) {
                    currentY > lastScrollY.current &&
                        setScrollToTopState(false);
                }
            }
            lastScrollY.current = currentY;
        }} ListEmptyComponent={<ListEmpty state={queryState} onRetry={() => {
                queryArtist(artistItem, 1, tab);
            }}/>} ListFooterComponent={data.data?.length ? <ListFooter state={queryState} onRetry={() => {
                queryArtist(artistItem, undefined, tab);
            }}/> : null} onEndReached={() => {
            (queryState === RequestStateCode.IDLE ||
                queryState === RequestStateCode.PARTLY_DONE) &&
                queryArtist(artistItem, undefined, tab);
        }} estimatedItemSize={ITEM_HEIGHT} overScrollMode="always" data={data.data ?? []} renderItem={renderItem}/>);
}
