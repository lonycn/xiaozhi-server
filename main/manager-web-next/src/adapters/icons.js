import * as icons from '@element-plus/icons-vue';

const aliases = {
  'arrow-left': 'ArrowLeft', 'arrow-right': 'ArrowRight', 'arrow-up': 'ArrowUp', 'arrow-down': 'ArrowDown',
  'arrow-up-bold': 'ArrowUpBold', 'arrow-down-bold': 'ArrowDownBold', 'arrow-left-bold': 'ArrowLeftBold', 'arrow-right-bold': 'ArrowRightBold',
  'caret-bottom': 'CaretBottom', 'caret-top': 'CaretTop', 'caret-left': 'CaretLeft', 'caret-right': 'CaretRight',
  'circle-check': 'CircleCheck', 'circle-check-outline': 'CircleCheck', 'circle-close': 'CircleClose', 'circle-close-outline': 'CircleClose',
  'edit-outline': 'EditPen', 'delete-solid': 'DeleteFilled', 's-custom': 'UserFilled', 's-tools': 'Tools', 's-operation': 'Operation',
  's-management': 'Management', 's-promotion': 'Promotion', 's-data': 'DataAnalysis', 's-order': 'List', 's-grid': 'Grid',
  's-fold': 'Fold', 's-unfold': 'Expand', 's-home': 'HomeFilled', 's-platform': 'Monitor', 's-comment': 'Comment',
  's-help': 'HelpFilled', 's-flag': 'Flag', 's-claim': 'Finished', 's-opportunity': 'Opportunity', 's-marketing': 'TrendCharts',
  's-shop': 'Shop', 's-check': 'Checked', 's-finance': 'Coin', 's-release': 'Release', 's-ticket': 'Ticket',
  'warning-outline': 'Warning', 'info': 'InfoFilled', 'error': 'CircleCloseFilled', 'success': 'SuccessFilled',
  'warning': 'WarningFilled', 'question': 'QuestionFilled', 'star-on': 'StarFilled', 'star-off': 'Star',
  'document-copy': 'DocumentCopy', 'document-checked': 'DocumentChecked', 'document-delete': 'DocumentDelete',
  'video-camera-solid': 'VideoCameraFilled', 'camera-solid': 'CameraFilled', 'picture-outline': 'Picture',
  'picture-outline-round': 'PictureRounded', 'location-outline': 'Location', 'date': 'Calendar', 'notebook-1': 'Notebook',
  'notebook-2': 'Notebook', 'reading': 'Reading', 'help': 'Help', 'loading': 'Loading', 'more-outline': 'More',
};

export function resolveLegacyIcon(value) {
  const legacy = String(value || '').split(/\s+/).find(name => name.startsWith('el-icon-')) || '';
  const name = legacy.slice(8);
  const candidate = aliases[name] || name.split('-').map(part => part[0]?.toUpperCase() + part.slice(1)).join('');
  return icons[candidate] || icons.QuestionFilled;
}

export function registerLegacyIcons(app) {
  for (const [name, component] of Object.entries(icons)) app.component(name, component);
  const legacyComponents = new Map();
  for (const [name, component] of Object.entries(icons)) {
    const legacy = `el-icon-${name.replace(/[A-Z]/g, (letter, index) => `${index ? '-' : ''}${letter.toLowerCase()}`)}`;
    legacyComponents.set(legacy, component);
  }
  for (const name of Object.keys(aliases)) legacyComponents.set(`el-icon-${name}`, resolveLegacyIcon(`el-icon-${name}`));
  for (const [name, component] of legacyComponents) app.component(name, component);
}
