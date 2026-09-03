import BackgroundTimer from "react-native-background-timer";
export default function (millsecond, background = true) {
    return new Promise(resolve => {
        if (background) {
            BackgroundTimer.setTimeout(resolve, millsecond);
        }
        else {
            setTimeout(resolve, millsecond);
        }
    });
}
