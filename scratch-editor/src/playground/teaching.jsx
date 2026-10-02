/**
 * teaching 平台专用入口：以 iframe 嵌入「积木创作台」页面。
 * 与 index.jsx 的差别：无 telemetry/logo 跳转，挂载 teaching-bridge。
 * HashParserHOC 必须保留——默认工程的装载由它设 projectId 触发，
 * 去掉它 VM 永远是零 target，桥也不会宣告就绪。
 * 语言由宿主以 ?locale=zh-cn 查询参数指定(detect-locale 原生支持)。
 */
import 'es6-object-assign/auto';
import 'core-js/fn/array/includes';
import 'core-js/fn/promise/finally';
import 'intl'; // For Safari 9

import React from 'react';
import ReactDOM from 'react-dom';
import {compose} from 'redux';

import AppStateHOC from '../lib/app-state-hoc.jsx';
import BrowserModalComponent from '../components/browser-modal/browser-modal.jsx';
import supportedBrowser from '../lib/supported-browser';

import styles from './index.css';

const appTarget = document.createElement('div');
appTarget.className = styles.app;
document.body.appendChild(appTarget);

if (supportedBrowser()) {
    // require here to avoid top-level import of browser-crashing code on old browsers
    const GUI = require('../containers/gui.jsx').default;
    const HashParserHOC = require('../lib/hash-parser-hoc.jsx').default;
    const installTeachingBridge = require('../lib/teaching-bridge').default;

    GUI.setAppElement(appTarget);
    const WrappedGui = compose(AppStateHOC, HashParserHOC)(GUI);

    ReactDOM.render(
        <WrappedGui
            canSave={false}
            showComingSoon
        />,
        appTarget
    );
    installTeachingBridge();
} else {
    BrowserModalComponent.setAppElement(appTarget);
    const WrappedBrowserModalComponent = AppStateHOC(BrowserModalComponent, true /* localesOnly */);
    const handleBack = () => {};
    // eslint-disable-next-line react/jsx-no-bind
    ReactDOM.render(<WrappedBrowserModalComponent onBack={handleBack} />, appTarget);
}
