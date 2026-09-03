import EventEmitter from "eventemitter3";
class EventBus {
    ee;
    constructor() {
        this.ee = new EventEmitter();
    }
    /**
     * 监听
     * @param eventName 事件名
     * @param callBack 回调
     */
    on(eventName, callBack) {
        this.ee.on(eventName, callBack);
    }
    once(eventName, callBack) {
        this.ee.once(eventName, callBack);
    }
    emit(eventName, payload) {
        this.ee.emit(eventName, payload);
    }
    off(eventName, callBack) {
        this.ee.off(eventName, callBack);
    }
}
export default EventBus;
