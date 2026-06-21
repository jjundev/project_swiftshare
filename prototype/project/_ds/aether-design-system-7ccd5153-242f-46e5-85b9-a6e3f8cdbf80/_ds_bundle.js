/* @ds-bundle: {"format":3,"namespace":"AetherDesignSystem_7ccd51","components":[{"name":"Badge","sourcePath":"components/core/Badge.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"Card","sourcePath":"components/core/Card.jsx"},{"name":"Banner","sourcePath":"components/feedback/Banner.jsx"},{"name":"Input","sourcePath":"components/forms/Input.jsx"},{"name":"Switch","sourcePath":"components/forms/Switch.jsx"},{"name":"SegmentedControl","sourcePath":"components/navigation/SegmentedControl.jsx"}],"sourceHashes":{"components/core/Badge.jsx":"6e625cd0e6d7","components/core/Button.jsx":"8cafcaa2ad77","components/core/Card.jsx":"50cb9a013cba","components/feedback/Banner.jsx":"7098089a453a","components/forms/Input.jsx":"7dd70c80df19","components/forms/Switch.jsx":"c4c4468503d3","components/navigation/SegmentedControl.jsx":"c31d17d13247","ui_kits/storefront/chrome.jsx":"d9ba2b37b3eb","ui_kits/storefront/data.js":"98275bbfb367","ui_kits/storefront/home.jsx":"3087686616ea","ui_kits/storefront/product.jsx":"43dd0d53e998"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.AetherDesignSystem_7ccd51 = window.AetherDesignSystem_7ccd51 || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/core/Badge.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const CSS = `
.ae-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-family: var(--font-sans);
  font-weight: var(--fw-semibold);
  font-size: var(--fs-caption);
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  line-height: 1;
  padding: 5px 9px;
  border-radius: var(--radius-pill);
  white-space: nowrap;
}
.ae-badge--lg { font-size: var(--fs-label); padding: 7px 12px; }
.ae-badge__dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }

/* Tone — subtle (tinted) */
.ae-badge--neutral  { color: var(--ink-600);      background: var(--ink-50); }
.ae-badge--accent   { color: var(--accent-700);   background: var(--accent-50); }
.ae-badge--positive { color: var(--positive-500);  background: var(--positive-50); }
.ae-badge--warning  { color: #8a6200;             background: var(--warning-50); }
.ae-badge--critical { color: var(--critical-500);  background: var(--critical-50); }
.ae-badge--info     { color: var(--info-500);      background: var(--info-50); }

/* Solid */
.ae-badge--solid.ae-badge--neutral  { color: #fff; background: var(--ink-900); }
.ae-badge--solid.ae-badge--accent   { color: #fff; background: var(--accent-500); }
.ae-badge--solid.ae-badge--positive { color: #fff; background: var(--positive-500); }
.ae-badge--solid.ae-badge--warning  { color: #fff; background: var(--warning-500); }
.ae-badge--solid.ae-badge--critical { color: #fff; background: var(--critical-500); }
.ae-badge--solid.ae-badge--info     { color: #fff; background: var(--info-500); }
`;
if (typeof document !== 'undefined' && !document.getElementById('ae-badge-css')) {
  const el = document.createElement('style');
  el.id = 'ae-badge-css';
  el.textContent = CSS;
  document.head.appendChild(el);
}
function Badge({
  tone = 'neutral',
  solid = false,
  size = 'md',
  dot = false,
  className = '',
  children,
  ...rest
}) {
  const cls = ['ae-badge', `ae-badge--${tone}`, solid ? 'ae-badge--solid' : '', size === 'lg' ? 'ae-badge--lg' : '', className].filter(Boolean).join(' ');
  return /*#__PURE__*/React.createElement("span", _extends({
    className: cls
  }, rest), dot && /*#__PURE__*/React.createElement("span", {
    className: "ae-badge__dot",
    "aria-hidden": "true"
  }), children);
}
Object.assign(__ds_scope, { Badge });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Badge.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/* Inject component CSS once, scoped by class prefix. Styling is driven
   entirely by Aether design tokens (CSS custom properties). */
const CSS = `
.ae-btn {
  --_fg: var(--action-primary-fg);
  --_bg: var(--action-primary-bg);
  --_bg-hover: var(--action-primary-bg-hover);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  font-family: var(--font-sans);
  font-weight: var(--fw-medium);
  letter-spacing: var(--ls-snug);
  white-space: nowrap;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  color: var(--_fg);
  background: var(--_bg);
  cursor: pointer;
  user-select: none;
  text-decoration: none;
  transition: var(--transition-control);
}
.ae-btn:hover { background: var(--_bg-hover); }
.ae-btn:active { transform: scale(0.97); }
.ae-btn:focus-visible { outline: none; box-shadow: var(--shadow-focus); }
.ae-btn[disabled], .ae-btn[aria-disabled="true"] {
  opacity: 0.4; cursor: not-allowed; transform: none; pointer-events: none;
}

/* Sizes */
.ae-btn--sm { height: 32px; padding-inline: var(--space-4); font-size: var(--fs-label); }
.ae-btn--md { height: 44px; padding-inline: var(--space-6); font-size: var(--fs-body-sm); }
.ae-btn--lg { height: 54px; padding-inline: var(--space-8); font-size: var(--fs-body); }

/* Variants */
.ae-btn--primary  { --_bg: var(--action-primary-bg);  --_bg-hover: var(--action-primary-bg-hover);  --_fg: var(--action-primary-fg); }
.ae-btn--accent   { --_bg: var(--action-accent-bg);   --_bg-hover: var(--action-accent-bg-hover);   --_fg: var(--action-accent-fg); }
.ae-btn--secondary{ --_bg: var(--action-secondary-bg);--_bg-hover: var(--action-secondary-bg-hover); --_fg: var(--action-secondary-fg); }
.ae-btn--ghost {
  --_bg: transparent; --_fg: var(--text-primary);
  border-color: var(--border-default);
}
.ae-btn--ghost:hover { background: var(--surface-muted); }
.ae-btn--link {
  --_bg: transparent; --_fg: var(--text-accent);
  height: auto; padding: 0; border-radius: 4px;
}
.ae-btn--link:hover { background: transparent; text-decoration: underline; text-underline-offset: 3px; }

.ae-btn--block { display: flex; width: 100%; }
.ae-btn__icon { display: inline-flex; flex: none; }
.ae-btn--sm .ae-btn__icon svg { width: 15px; height: 15px; }
.ae-btn--md .ae-btn__icon svg { width: 18px; height: 18px; }
.ae-btn--lg .ae-btn__icon svg { width: 20px; height: 20px; }
`;
if (typeof document !== 'undefined' && !document.getElementById('ae-button-css')) {
  const el = document.createElement('style');
  el.id = 'ae-button-css';
  el.textContent = CSS;
  document.head.appendChild(el);
}
function Button({
  variant = 'primary',
  size = 'md',
  href,
  type = 'button',
  iconLeft,
  iconRight,
  fullWidth = false,
  disabled = false,
  className = '',
  children,
  ...rest
}) {
  const cls = ['ae-btn', `ae-btn--${variant}`, variant !== 'link' ? `ae-btn--${size}` : '', fullWidth ? 'ae-btn--block' : '', className].filter(Boolean).join(' ');
  const content = /*#__PURE__*/React.createElement(React.Fragment, null, iconLeft && /*#__PURE__*/React.createElement("span", {
    className: "ae-btn__icon",
    "aria-hidden": "true"
  }, iconLeft), children && /*#__PURE__*/React.createElement("span", {
    className: "ae-btn__label"
  }, children), iconRight && /*#__PURE__*/React.createElement("span", {
    className: "ae-btn__icon",
    "aria-hidden": "true"
  }, iconRight));
  if (href && !disabled) {
    return /*#__PURE__*/React.createElement("a", _extends({
      className: cls,
      href: href
    }, rest), content);
  }
  return /*#__PURE__*/React.createElement("button", _extends({
    className: cls,
    type: type,
    disabled: disabled
  }, rest), content);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/core/Card.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const CSS = `
.ae-card {
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-radius: var(--radius-lg);
  overflow: hidden;
  transition: var(--transition-control);
}
.ae-card--outlined { box-shadow: var(--ring-hairline); }
.ae-card--raised   { box-shadow: var(--shadow-sm); }
.ae-card--muted    { background: var(--surface-muted); }
.ae-card--feature  { border-radius: var(--radius-xl); }

a.ae-card, .ae-card--interactive { cursor: pointer; text-decoration: none; color: inherit; }
a.ae-card:hover, .ae-card--interactive:hover { transform: translateY(-3px); box-shadow: var(--shadow-md); }
a.ae-card:focus-visible { outline: none; box-shadow: var(--shadow-focus); }

.ae-card__media {
  position: relative;
  aspect-ratio: 16 / 10;
  background: var(--surface-sunken);
  overflow: hidden;
}
.ae-card__media > img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ae-card__media--tall { aspect-ratio: 4 / 5; }

.ae-card__body {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-6);
}
.ae-card--feature .ae-card__body { padding: var(--space-8); gap: var(--space-3); }

.ae-card__eyebrow {
  font-size: var(--fs-label);
  font-weight: var(--fw-semibold);
  letter-spacing: var(--ls-wide);
  text-transform: uppercase;
  color: var(--text-accent);
}
.ae-card__title {
  font-size: var(--fs-headline-sm);
  font-weight: var(--fw-semibold);
  letter-spacing: var(--ls-tight);
  line-height: var(--lh-snug);
  color: var(--text-primary);
}
.ae-card--feature .ae-card__title { font-size: var(--fs-headline-md); }
.ae-card__desc {
  font-size: var(--fs-body-sm);
  line-height: var(--lh-relaxed);
  color: var(--text-secondary);
}
.ae-card__footer {
  margin-top: var(--space-2);
  display: flex;
  align-items: center;
  gap: var(--space-4);
}
`;
if (typeof document !== 'undefined' && !document.getElementById('ae-card-css')) {
  const el = document.createElement('style');
  el.id = 'ae-card-css';
  el.textContent = CSS;
  document.head.appendChild(el);
}
function Card({
  variant = 'outlined',
  feature = false,
  href,
  media,
  tallMedia = false,
  eyebrow,
  title,
  description,
  footer,
  className = '',
  children,
  ...rest
}) {
  const cls = ['ae-card', `ae-card--${variant}`, feature ? 'ae-card--feature' : '', href ? 'ae-card--interactive' : '', className].filter(Boolean).join(' ');
  const inner = /*#__PURE__*/React.createElement(React.Fragment, null, media && /*#__PURE__*/React.createElement("div", {
    className: `ae-card__media${tallMedia ? ' ae-card__media--tall' : ''}`
  }, media), (eyebrow || title || description || children || footer) && /*#__PURE__*/React.createElement("div", {
    className: "ae-card__body"
  }, eyebrow && /*#__PURE__*/React.createElement("span", {
    className: "ae-card__eyebrow"
  }, eyebrow), title && /*#__PURE__*/React.createElement("h3", {
    className: "ae-card__title"
  }, title), description && /*#__PURE__*/React.createElement("p", {
    className: "ae-card__desc"
  }, description), children, footer && /*#__PURE__*/React.createElement("div", {
    className: "ae-card__footer"
  }, footer)));
  if (href) {
    return /*#__PURE__*/React.createElement("a", _extends({
      className: cls,
      href: href
    }, rest), inner);
  }
  return /*#__PURE__*/React.createElement("div", _extends({
    className: cls
  }, rest), inner);
}
Object.assign(__ds_scope, { Card });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Card.jsx", error: String((e && e.message) || e) }); }

// components/feedback/Banner.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const CSS = `
.ae-banner {
  display: flex;
  align-items: flex-start;
  gap: var(--space-3);
  padding: var(--space-4) var(--space-5);
  border-radius: var(--radius-md);
  font-family: var(--font-sans);
  font-size: var(--fs-body-sm);
  line-height: var(--lh-relaxed);
  color: var(--text-primary);
}
.ae-banner__icon { flex: none; display: inline-flex; margin-top: 1px; }
.ae-banner__icon svg { width: 20px; height: 20px; }
.ae-banner__body { flex: 1; min-width: 0; }
.ae-banner__title { font-weight: var(--fw-semibold); }
.ae-banner__text { color: var(--text-secondary); }
.ae-banner__title + .ae-banner__text { margin-top: 2px; }
.ae-banner__actions { margin-top: var(--space-3); display: flex; gap: var(--space-3); }

.ae-banner--neutral  { background: var(--ink-50);       box-shadow: inset 0 0 0 1px var(--border-subtle); }
.ae-banner--neutral  .ae-banner__icon { color: var(--ink-500); }
.ae-banner--accent   { background: var(--accent-50);    }
.ae-banner--accent   .ae-banner__icon { color: var(--accent-600); }
.ae-banner--positive { background: var(--positive-50);  }
.ae-banner--positive .ae-banner__icon { color: var(--positive-500); }
.ae-banner--warning  { background: var(--warning-50);   }
.ae-banner--warning  .ae-banner__icon { color: #8a6200; }
.ae-banner--critical { background: var(--critical-50);  }
.ae-banner--critical .ae-banner__icon { color: var(--critical-500); }
.ae-banner--info     { background: var(--info-50);      }
.ae-banner--info     .ae-banner__icon { color: var(--info-500); }
`;
if (typeof document !== 'undefined' && !document.getElementById('ae-banner-css')) {
  const el = document.createElement('style');
  el.id = 'ae-banner-css';
  el.textContent = CSS;
  document.head.appendChild(el);
}
function Banner({
  tone = 'neutral',
  icon,
  title,
  actions,
  className = '',
  children,
  ...rest
}) {
  const cls = ['ae-banner', `ae-banner--${tone}`, className].filter(Boolean).join(' ');
  return /*#__PURE__*/React.createElement("div", _extends({
    className: cls,
    role: "status"
  }, rest), icon && /*#__PURE__*/React.createElement("span", {
    className: "ae-banner__icon",
    "aria-hidden": "true"
  }, icon), /*#__PURE__*/React.createElement("div", {
    className: "ae-banner__body"
  }, title && /*#__PURE__*/React.createElement("div", {
    className: "ae-banner__title"
  }, title), children && /*#__PURE__*/React.createElement("div", {
    className: "ae-banner__text"
  }, children), actions && /*#__PURE__*/React.createElement("div", {
    className: "ae-banner__actions"
  }, actions)));
}
Object.assign(__ds_scope, { Banner });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/feedback/Banner.jsx", error: String((e && e.message) || e) }); }

// components/forms/Input.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const CSS = `
.ae-field { display: flex; flex-direction: column; gap: var(--space-2); font-family: var(--font-sans); }
.ae-field__label {
  font-size: var(--fs-label);
  font-weight: var(--fw-medium);
  color: var(--text-primary);
}
.ae-field__optional { color: var(--text-tertiary); font-weight: var(--fw-regular); }
.ae-field__control {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  height: 48px;
  padding-inline: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  transition: var(--transition-control);
}
.ae-field__control:hover { border-color: var(--border-strong); }
.ae-field__control:focus-within {
  border-color: var(--accent-500);
  box-shadow: 0 0 0 3px var(--accent-50);
}
.ae-field__input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-family: inherit;
  font-size: var(--fs-body);
  color: var(--text-primary);
}
.ae-field__input::placeholder { color: var(--text-tertiary); }
.ae-field__affix { display: inline-flex; color: var(--text-tertiary); flex: none; }
.ae-field__affix svg { width: 18px; height: 18px; }
.ae-field__hint { font-size: var(--fs-label); color: var(--text-secondary); }

.ae-field--error .ae-field__control { border-color: var(--critical-500); }
.ae-field--error .ae-field__control:focus-within { box-shadow: 0 0 0 3px var(--critical-50); }
.ae-field--error .ae-field__hint { color: var(--critical-500); }

.ae-field--disabled { opacity: 0.5; pointer-events: none; }
.ae-field--disabled .ae-field__control { background: var(--surface-muted); }
`;
if (typeof document !== 'undefined' && !document.getElementById('ae-input-css')) {
  const el = document.createElement('style');
  el.id = 'ae-input-css';
  el.textContent = CSS;
  document.head.appendChild(el);
}
function Input({
  label,
  hint,
  error,
  optional = false,
  iconLeft,
  iconRight,
  disabled = false,
  id,
  className = '',
  ...rest
}) {
  const fid = id || `ae-input-${Math.random().toString(36).slice(2, 8)}`;
  const cls = ['ae-field', error ? 'ae-field--error' : '', disabled ? 'ae-field--disabled' : '', className].filter(Boolean).join(' ');
  return /*#__PURE__*/React.createElement("div", {
    className: cls
  }, label && /*#__PURE__*/React.createElement("label", {
    className: "ae-field__label",
    htmlFor: fid
  }, label, optional && /*#__PURE__*/React.createElement("span", {
    className: "ae-field__optional"
  }, " \u2014 optional")), /*#__PURE__*/React.createElement("div", {
    className: "ae-field__control"
  }, iconLeft && /*#__PURE__*/React.createElement("span", {
    className: "ae-field__affix",
    "aria-hidden": "true"
  }, iconLeft), /*#__PURE__*/React.createElement("input", _extends({
    className: "ae-field__input",
    id: fid,
    disabled: disabled
  }, rest)), iconRight && /*#__PURE__*/React.createElement("span", {
    className: "ae-field__affix",
    "aria-hidden": "true"
  }, iconRight)), (error || hint) && /*#__PURE__*/React.createElement("span", {
    className: "ae-field__hint"
  }, error || hint));
}
Object.assign(__ds_scope, { Input });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Input.jsx", error: String((e && e.message) || e) }); }

// components/forms/Switch.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const CSS = `
.ae-switch {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3);
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: var(--fs-body-sm);
  color: var(--text-primary);
  user-select: none;
}
.ae-switch__track {
  position: relative;
  flex: none;
  width: 48px;
  height: 28px;
  border-radius: var(--radius-pill);
  background: var(--ink-200);
  transition: background-color var(--dur-base) var(--ease-standard);
}
.ae-switch__thumb {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #fff;
  box-shadow: var(--shadow-sm);
  transition: transform var(--dur-base) var(--ease-standard);
}
.ae-switch input { position: absolute; opacity: 0; width: 0; height: 0; }
.ae-switch input:checked + .ae-switch__track { background: var(--accent-500); }
.ae-switch input:checked + .ae-switch__track .ae-switch__thumb { transform: translateX(20px); }
.ae-switch input:focus-visible + .ae-switch__track { box-shadow: var(--shadow-focus); }
.ae-switch--disabled { opacity: 0.4; pointer-events: none; }
`;
if (typeof document !== 'undefined' && !document.getElementById('ae-switch-css')) {
  const el = document.createElement('style');
  el.id = 'ae-switch-css';
  el.textContent = CSS;
  document.head.appendChild(el);
}
function Switch({
  checked,
  defaultChecked,
  onChange,
  label,
  disabled = false,
  className = '',
  ...rest
}) {
  const cls = ['ae-switch', disabled ? 'ae-switch--disabled' : '', className].filter(Boolean).join(' ');
  return /*#__PURE__*/React.createElement("label", {
    className: cls
  }, /*#__PURE__*/React.createElement("input", _extends({
    type: "checkbox",
    role: "switch",
    checked: checked,
    defaultChecked: defaultChecked,
    onChange: onChange,
    disabled: disabled
  }, rest)), /*#__PURE__*/React.createElement("span", {
    className: "ae-switch__track",
    "aria-hidden": "true"
  }, /*#__PURE__*/React.createElement("span", {
    className: "ae-switch__thumb"
  })), label && /*#__PURE__*/React.createElement("span", {
    className: "ae-switch__label"
  }, label));
}
Object.assign(__ds_scope, { Switch });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Switch.jsx", error: String((e && e.message) || e) }); }

// components/navigation/SegmentedControl.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
const CSS = `
.ae-seg {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
  font-family: var(--font-sans);
}
.ae-seg__item {
  appearance: none;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
  font-size: var(--fs-body-sm);
  font-weight: var(--fw-medium);
  letter-spacing: var(--ls-snug);
  color: var(--text-secondary);
  padding: 8px 18px;
  border-radius: var(--radius-pill);
  transition: var(--transition-control);
  white-space: nowrap;
}
.ae-seg__item:hover { color: var(--text-primary); }
.ae-seg__item:focus-visible { outline: none; box-shadow: var(--shadow-focus); }
.ae-seg__item--active {
  background: var(--surface);
  color: var(--text-primary);
  box-shadow: var(--shadow-xs);
}
.ae-seg--lg .ae-seg__item { padding: 11px 24px; font-size: var(--fs-body); }
`;
if (typeof document !== 'undefined' && !document.getElementById('ae-seg-css')) {
  const el = document.createElement('style');
  el.id = 'ae-seg-css';
  el.textContent = CSS;
  document.head.appendChild(el);
}
function SegmentedControl({
  options = [],
  value,
  defaultValue,
  onChange,
  size = 'md',
  className = '',
  ...rest
}) {
  const isControlled = value !== undefined;
  const [internal, setInternal] = React.useState(defaultValue ?? (options[0] && (options[0].value ?? options[0])));
  const current = isControlled ? value : internal;
  const handle = val => {
    if (!isControlled) setInternal(val);
    onChange && onChange(val);
  };
  const cls = ['ae-seg', size === 'lg' ? 'ae-seg--lg' : '', className].filter(Boolean).join(' ');
  return /*#__PURE__*/React.createElement("div", _extends({
    className: cls,
    role: "tablist"
  }, rest), options.map(opt => {
    const val = opt.value ?? opt;
    const label = opt.label ?? opt;
    const active = val === current;
    return /*#__PURE__*/React.createElement("button", {
      key: val,
      type: "button",
      role: "tab",
      "aria-selected": active,
      className: `ae-seg__item${active ? ' ae-seg__item--active' : ''}`,
      onClick: () => handle(val)
    }, label);
  }));
}
Object.assign(__ds_scope, { SegmentedControl });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/navigation/SegmentedControl.jsx", error: String((e && e.message) || e) }); }

// ui_kits/storefront/chrome.jsx
try { (() => {
/* Aether storefront — shared chrome: nav, footer, media tiles, icon helper. */

/* Lucide icon as <i data-lucide>; App re-runs createIcons after each render. */
function Ico({
  name,
  size = 20,
  color,
  style
}) {
  return /*#__PURE__*/React.createElement("i", {
    "data-lucide": name,
    style: {
      width: size,
      height: size,
      color,
      display: 'inline-flex',
      ...style
    }
  });
}

/* Art-directed product placeholder (no photography in this kit). */
function ProductMedia({
  glyph,
  tone = 'mist',
  size = 96
}) {
  const bg = {
    mist: 'radial-gradient(130% 130% at 70% 15%, #ffffff, #ececea)',
    coral: 'radial-gradient(130% 130% at 70% 15%, var(--accent-50), #f3ddd4)',
    ink: 'radial-gradient(130% 130% at 70% 15%, #2b2c33, #15161a)'
  }[tone];
  const fg = tone === 'ink' ? '#e9e9ec' : 'var(--ink-700)';
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      inset: 0,
      display: 'grid',
      placeItems: 'center',
      background: bg
    }
  }, /*#__PURE__*/React.createElement("i", {
    "data-lucide": glyph,
    style: {
      width: size,
      height: size,
      color: fg,
      strokeWidth: 1.25
    }
  }));
}
function Logo({
  inverse = false,
  onClick
}) {
  return /*#__PURE__*/React.createElement("button", {
    onClick: onClick,
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 9,
      background: 'none',
      border: 'none',
      cursor: 'pointer',
      padding: 0
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: inverse ? '../../assets/aether-mark-inverse.svg' : '../../assets/aether-mark.svg',
    alt: "Aether",
    style: {
      width: 26,
      height: 26
    }
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 21,
      fontWeight: 600,
      letterSpacing: '-0.02em',
      color: inverse ? '#fff' : 'var(--ink-900)'
    }
  }, "aether"));
}
function GlobalNav({
  onHome,
  onProduct,
  bagCount,
  onBag
}) {
  const {
    nav
  } = window.AE_DATA;
  return /*#__PURE__*/React.createElement("header", {
    style: {
      position: 'sticky',
      top: 0,
      zIndex: 50,
      height: 'var(--nav-height)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '0 28px',
      background: 'rgba(255,255,255,0.8)',
      backdropFilter: 'saturate(180%) blur(20px)',
      WebkitBackdropFilter: 'saturate(180%) blur(20px)',
      borderBottom: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement(Logo, {
    onClick: onHome
  }), /*#__PURE__*/React.createElement("nav", {
    style: {
      display: 'flex',
      gap: 4
    }
  }, nav.map(n => /*#__PURE__*/React.createElement("button", {
    key: n,
    onClick: () => onProduct && onProduct(n),
    style: {
      background: 'none',
      border: 'none',
      cursor: 'pointer',
      font: 'inherit',
      fontSize: 'var(--fs-body-sm)',
      fontWeight: 'var(--fw-medium)',
      color: 'var(--ink-700)',
      padding: '6px 12px',
      borderRadius: 'var(--radius-pill)'
    },
    onMouseEnter: e => e.currentTarget.style.color = 'var(--ink-900)',
    onMouseLeave: e => e.currentTarget.style.color = 'var(--ink-700)'
  }, n))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 6
    }
  }, /*#__PURE__*/React.createElement("button", {
    style: iconBtn
  }, /*#__PURE__*/React.createElement(Ico, {
    name: "search",
    size: 18
  })), /*#__PURE__*/React.createElement("button", {
    style: {
      ...iconBtn,
      position: 'relative'
    },
    onClick: onBag
  }, /*#__PURE__*/React.createElement(Ico, {
    name: "shopping-bag",
    size: 18
  }), bagCount > 0 && /*#__PURE__*/React.createElement("span", {
    style: bagDot
  }, bagCount))));
}
const iconBtn = {
  display: 'grid',
  placeItems: 'center',
  width: 36,
  height: 36,
  borderRadius: '50%',
  background: 'none',
  border: 'none',
  cursor: 'pointer',
  color: 'var(--ink-700)'
};
const bagDot = {
  position: 'absolute',
  top: 2,
  right: 2,
  minWidth: 16,
  height: 16,
  padding: '0 4px',
  borderRadius: 8,
  background: 'var(--accent-500)',
  color: '#fff',
  fontSize: 10,
  fontWeight: 700,
  display: 'grid',
  placeItems: 'center'
};
function Footer() {
  const cols = [['Shop', ['Slate', 'Pulse', 'Halo', 'Aura', 'Accessories']], ['Account', ['Manage your ID', 'Orders', 'Trade in', 'Financing']], ['Aether', ['Newsroom', 'Careers', 'Sustainability', 'Contact']], ['Support', ['Help centre', 'Returns', 'Warranty', 'Find a store']]];
  return /*#__PURE__*/React.createElement("footer", {
    style: {
      background: 'var(--surface-muted)',
      padding: '48px 28px 28px',
      color: 'var(--ink-600)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: 'var(--container-max)',
      margin: '0 auto'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: 'repeat(4, 1fr)',
      gap: 24,
      paddingBottom: 32,
      borderBottom: '1px solid var(--border-default)'
    }
  }, cols.map(([h, items]) => /*#__PURE__*/React.createElement("div", {
    key: h
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      fontWeight: 700,
      color: 'var(--ink-900)',
      marginBottom: 12
    }
  }, h), /*#__PURE__*/React.createElement("ul", {
    style: {
      listStyle: 'none',
      margin: 0,
      padding: 0,
      display: 'flex',
      flexDirection: 'column',
      gap: 9
    }
  }, items.map(i => /*#__PURE__*/React.createElement("li", {
    key: i,
    style: {
      fontSize: 13
    }
  }, i)))))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      paddingTop: 20,
      fontSize: 12
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "../../assets/aether-mark-mono.svg",
    alt: "",
    style: {
      width: 18,
      height: 18
    }
  }), /*#__PURE__*/React.createElement("span", null, "\xA9 2026 Aether. A fictional brand.")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 18
    }
  }, /*#__PURE__*/React.createElement("span", null, "Privacy"), /*#__PURE__*/React.createElement("span", null, "Terms"), /*#__PURE__*/React.createElement("span", null, "Sales policy")))));
}
Object.assign(window, {
  Ico,
  ProductMedia,
  Logo,
  GlobalNav,
  Footer
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/storefront/chrome.jsx", error: String((e && e.message) || e) }); }

// ui_kits/storefront/data.js
try { (() => {
/* Aether storefront — demo catalogue data (window.AE_DATA). */
window.AE_DATA = {
  nav: ['Slate', 'Pulse', 'Halo', 'Aura', 'Support'],
  products: [{
    id: 'slate-pro',
    name: 'Slate Pro',
    tagline: 'Power, quietly delivered.',
    glyph: 'laptop-minimal',
    category: 'Laptop',
    badge: 'New',
    from: 1599,
    blurb: 'The most capable Slate we have ever made, with the fanless M4 Pro engine and an all-day battery.',
    sizes: [{
      label: '14-inch',
      value: '14'
    }, {
      label: '16-inch',
      value: '16'
    }],
    finishes: [{
      label: 'Graphite',
      value: 'graphite',
      hex: '#3a3b42'
    }, {
      label: 'Silver',
      value: 'silver',
      hex: '#d7d8dc'
    }, {
      label: 'Midnight',
      value: 'midnight',
      hex: '#1b1f2a'
    }],
    storage: [{
      label: '512GB',
      value: '512',
      add: 0
    }, {
      label: '1TB',
      value: '1024',
      add: 200
    }, {
      label: '2TB',
      value: '2048',
      add: 600
    }],
    specs: [['Display', '14.2″ Liquid'], ['Chip', 'M4 Pro'], ['Battery', '22 hr'], ['Weight', '1.55 kg']]
  }, {
    id: 'pulse-3',
    name: 'Pulse 3',
    tagline: 'A brighter every day.',
    glyph: 'watch',
    category: 'Watch',
    badge: null,
    from: 429,
    blurb: 'A brighter always-on display, two-day battery and the most accurate sensors we have built.',
    sizes: [{
      label: '41mm',
      value: '41'
    }, {
      label: '45mm',
      value: '45'
    }],
    finishes: [{
      label: 'Graphite',
      value: 'graphite',
      hex: '#3a3b42'
    }, {
      label: 'Coral',
      value: 'coral',
      hex: '#ff5a36'
    }, {
      label: 'Mist',
      value: 'mist',
      hex: '#c8d0d8'
    }],
    storage: [{
      label: 'GPS',
      value: 'gps',
      add: 0
    }, {
      label: 'GPS + Cellular',
      value: 'cell',
      add: 120
    }],
    specs: [['Display', 'Always-on'], ['Battery', '48 hr'], ['Water', '50m'], ['Weight', '32 g']]
  }, {
    id: 'halo',
    name: 'Halo',
    tagline: 'Sound, all around.',
    glyph: 'headphones',
    category: 'Audio',
    badge: null,
    from: 549,
    blurb: 'Adaptive audio that reads the room, with active noise cancellation and 30-hour playback.',
    sizes: [{
      label: 'Standard',
      value: 'std'
    }],
    finishes: [{
      label: 'Graphite',
      value: 'graphite',
      hex: '#3a3b42'
    }, {
      label: 'Bone',
      value: 'bone',
      hex: '#ece7df'
    }, {
      label: 'Coral',
      value: 'coral',
      hex: '#ff5a36'
    }],
    storage: [{
      label: 'Over-ear',
      value: 'over',
      add: 0
    }],
    specs: [['Battery', '30 hr'], ['ANC', 'Adaptive'], ['Charge', 'USB-C'], ['Weight', '255 g']]
  }, {
    id: 'aura',
    name: 'Aura',
    tagline: 'Light that listens.',
    glyph: 'speaker',
    category: 'Home',
    badge: null,
    from: 299,
    blurb: 'A room-filling speaker and ambient light that tunes itself to the time of day.',
    sizes: [{
      label: 'Mini',
      value: 'mini'
    }, {
      label: 'Full',
      value: 'full'
    }],
    finishes: [{
      label: 'Graphite',
      value: 'graphite',
      hex: '#3a3b42'
    }, {
      label: 'Bone',
      value: 'bone',
      hex: '#ece7df'
    }],
    storage: [{
      label: 'Wi-Fi',
      value: 'wifi',
      add: 0
    }],
    specs: [['Sound', '360°'], ['Light', 'Adaptive'], ['Voice', 'Built-in'], ['Power', 'Mains']]
  }]
};
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/storefront/data.js", error: String((e && e.message) || e) }); }

// ui_kits/storefront/home.jsx
try { (() => {
/* Aether storefront — Home view: hero, lineup grid, feature band. */

function Hero({
  onProduct
}) {
  const {
    Button,
    Badge
  } = window.AetherDesignSystem_7ccd51;
  return /*#__PURE__*/React.createElement("section", {
    style: {
      padding: '76px 28px 64px',
      textAlign: 'center'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'inline-flex',
      marginBottom: 20
    }
  }, /*#__PURE__*/React.createElement(Badge, {
    tone: "accent"
  }, "New \xB7 Slate Pro")), /*#__PURE__*/React.createElement("h1", {
    style: {
      fontSize: 'clamp(44px, 7vw, 80px)',
      fontWeight: 700,
      letterSpacing: '-0.03em',
      lineHeight: 1.04,
      margin: '0 auto',
      maxWidth: 860
    }
  }, "Power, quietly", /*#__PURE__*/React.createElement("br", null), "delivered."), /*#__PURE__*/React.createElement("p", {
    style: {
      fontSize: 'var(--fs-body-lg)',
      color: 'var(--ink-500)',
      maxWidth: 560,
      margin: '18px auto 0',
      lineHeight: 1.5
    }
  }, "The new Slate Pro runs cooler, lasts longer and never raises its voice. Meet the most capable laptop we have ever made."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 12,
      justifyContent: 'center',
      marginTop: 28
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "accent",
    size: "lg",
    onClick: () => onProduct('Slate')
  }, "Pre-order"), /*#__PURE__*/React.createElement(Button, {
    variant: "ghost",
    size: "lg",
    onClick: () => onProduct('Slate')
  }, "Learn more")), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative',
      height: 340,
      maxWidth: 920,
      margin: '40px auto 0',
      borderRadius: 'var(--radius-2xl)',
      overflow: 'hidden',
      boxShadow: 'var(--shadow-lg)'
    }
  }, /*#__PURE__*/React.createElement(ProductMedia, {
    glyph: "laptop-minimal",
    tone: "ink",
    size: 150
  })));
}
function Lineup({
  onProduct
}) {
  const {
    Card,
    Button
  } = window.AetherDesignSystem_7ccd51;
  const {
    products
  } = window.AE_DATA;
  const tones = {
    'slate-pro': 'mist',
    'pulse-3': 'coral',
    'halo': 'mist',
    'aura': 'mist'
  };
  return /*#__PURE__*/React.createElement("section", {
    style: {
      padding: '8px 28px 64px',
      maxWidth: 'var(--container-max)',
      margin: '0 auto'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'baseline',
      justifyContent: 'space-between',
      marginBottom: 24
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontSize: 'var(--fs-headline-md)',
      fontWeight: 600,
      letterSpacing: '-0.02em'
    }
  }, "Explore the lineup"), /*#__PURE__*/React.createElement(Button, {
    variant: "link",
    onClick: () => onProduct('Slate')
  }, "All products \u203A")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: 'repeat(4, 1fr)',
      gap: 16
    }
  }, products.map(p => /*#__PURE__*/React.createElement(Card, {
    key: p.id,
    href: "#",
    onClick: e => {
      e.preventDefault();
      onProduct(p.name);
    },
    media: /*#__PURE__*/React.createElement(ProductMedia, {
      glyph: p.glyph,
      tone: tones[p.id],
      size: 84
    }),
    eyebrow: p.category,
    title: p.name,
    description: p.tagline,
    footer: /*#__PURE__*/React.createElement("span", {
      style: {
        fontFamily: 'var(--font-mono)',
        fontSize: 13,
        color: 'var(--ink-700)'
      }
    }, "From $", p.from)
  }))));
}
function FeatureBand() {
  const feats = [['leaf', 'Carbon neutral', 'Every Aether device ships carbon neutral, in plastic-free packaging.'], ['battery-charging', 'Two-day battery', 'Engineered efficiency means you charge less and live more.'], ['shield-check', 'Three-year care', 'Complimentary cover and free repairs, included as standard.']];
  return /*#__PURE__*/React.createElement("section", {
    style: {
      background: 'var(--surface-inverse)',
      color: '#fff',
      padding: '64px 28px'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: 'var(--container-max)',
      margin: '0 auto'
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontSize: 'var(--fs-headline-md)',
      fontWeight: 600,
      letterSpacing: '-0.02em',
      color: '#fff',
      maxWidth: 520
    }
  }, "Considered down to the last detail."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: 'repeat(3, 1fr)',
      gap: 28,
      marginTop: 36
    }
  }, feats.map(([icon, h, b]) => /*#__PURE__*/React.createElement("div", {
    key: h
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      width: 44,
      height: 44,
      borderRadius: 12,
      background: 'rgba(255,255,255,0.08)',
      display: 'grid',
      placeItems: 'center',
      marginBottom: 14
    }
  }, /*#__PURE__*/React.createElement(Ico, {
    name: icon,
    size: 22,
    color: "var(--accent-400)"
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 19,
      fontWeight: 600,
      marginBottom: 6
    }
  }, h), /*#__PURE__*/React.createElement("p", {
    style: {
      fontSize: 14,
      color: 'var(--ink-300)',
      lineHeight: 1.5
    }
  }, b))))));
}
function HomeView({
  onProduct
}) {
  return /*#__PURE__*/React.createElement("main", null, /*#__PURE__*/React.createElement(Hero, {
    onProduct: onProduct
  }), /*#__PURE__*/React.createElement(Lineup, {
    onProduct: onProduct
  }), /*#__PURE__*/React.createElement(FeatureBand, null));
}
Object.assign(window, {
  HomeView
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/storefront/home.jsx", error: String((e && e.message) || e) }); }

// ui_kits/storefront/product.jsx
try { (() => {
/* Aether storefront — Product configurator + Bag drawer. */

function FinishSwatches({
  finishes,
  value,
  onChange
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      gap: 10
    }
  }, finishes.map(f => {
    const active = f.value === value;
    return /*#__PURE__*/React.createElement("button", {
      key: f.value,
      onClick: () => onChange(f.value),
      title: f.label,
      style: {
        width: 34,
        height: 34,
        borderRadius: '50%',
        cursor: 'pointer',
        background: f.hex,
        border: '1px solid rgba(20,22,26,0.12)',
        boxShadow: active ? '0 0 0 2px var(--surface), 0 0 0 4px var(--accent-500)' : 'none',
        transition: 'box-shadow var(--dur-fast) var(--ease-standard)'
      }
    });
  }));
}
function ConfigRow({
  label,
  children
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      flexDirection: 'column',
      gap: 10,
      paddingBlock: 18,
      borderTop: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 13,
      fontWeight: 600,
      letterSpacing: '0.04em',
      textTransform: 'uppercase',
      color: 'var(--ink-500)'
    }
  }, label), children);
}
function ProductView({
  product,
  onAdd
}) {
  const {
    SegmentedControl,
    Button,
    Badge,
    Banner
  } = window.AetherDesignSystem_7ccd51;
  const p = product;
  const [size, setSize] = React.useState(p.sizes[0].value);
  const [finish, setFinish] = React.useState(p.finishes[0].value);
  const [storage, setStorage] = React.useState(p.storage[0].value);
  const add = p.storage.find(s => s.value === storage)?.add || 0;
  const price = p.from + add;
  const finishObj = p.finishes.find(f => f.value === finish);
  const tone = finishObj?.value === 'coral' ? 'coral' : 'mist';
  return /*#__PURE__*/React.createElement("main", null, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: '1.1fr 0.9fr',
      gap: 0,
      maxWidth: 'var(--container-wide)',
      margin: '0 auto',
      minHeight: 'calc(100vh - var(--nav-height))'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'sticky',
      top: 'var(--nav-height)',
      alignSelf: 'start',
      height: 'calc(100vh - var(--nav-height))'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative',
      height: '100%',
      minHeight: 480
    }
  }, /*#__PURE__*/React.createElement(ProductMedia, {
    glyph: p.glyph,
    tone: tone,
    size: 190
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'absolute',
      left: 28,
      top: 28,
      display: 'flex',
      gap: 8
    }
  }, p.badge && /*#__PURE__*/React.createElement(Badge, {
    tone: "accent"
  }, p.badge), /*#__PURE__*/React.createElement(Badge, {
    tone: "neutral",
    solid: true
  }, finishObj?.label)))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '52px 40px 64px',
      maxWidth: 520
    }
  }, /*#__PURE__*/React.createElement("span", {
    className: "ae-eyebrow"
  }, p.category), /*#__PURE__*/React.createElement("h1", {
    style: {
      fontSize: 'var(--fs-headline-lg)',
      fontWeight: 700,
      letterSpacing: '-0.02em',
      margin: '8px 0 4px'
    }
  }, p.name), /*#__PURE__*/React.createElement("p", {
    style: {
      fontSize: 'var(--fs-body-lg)',
      color: 'var(--ink-500)',
      lineHeight: 1.5
    }
  }, p.blurb), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 20
    }
  }, p.sizes.length > 1 && /*#__PURE__*/React.createElement(ConfigRow, {
    label: "Size"
  }, /*#__PURE__*/React.createElement(SegmentedControl, {
    options: p.sizes,
    value: size,
    onChange: setSize
  })), /*#__PURE__*/React.createElement(ConfigRow, {
    label: `Finish — ${finishObj?.label}`
  }, /*#__PURE__*/React.createElement(FinishSwatches, {
    finishes: p.finishes,
    value: finish,
    onChange: setFinish
  })), p.storage.length > 1 && /*#__PURE__*/React.createElement(ConfigRow, {
    label: "Configuration"
  }, /*#__PURE__*/React.createElement(SegmentedControl, {
    options: p.storage.map(s => ({
      label: s.label,
      value: s.value
    })),
    value: storage,
    onChange: setStorage
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'grid',
      gridTemplateColumns: 'repeat(4, 1fr)',
      gap: 12,
      margin: '24px 0',
      padding: '18px 0',
      borderTop: '1px solid var(--border-subtle)',
      borderBottom: '1px solid var(--border-subtle)'
    }
  }, p.specs.map(([k, v]) => /*#__PURE__*/React.createElement("div", {
    key: k
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: 'var(--font-mono)',
      fontSize: 18,
      fontWeight: 600,
      color: 'var(--ink-900)'
    }
  }, v), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: 'var(--ink-500)'
    }
  }, k)))), /*#__PURE__*/React.createElement(Banner, {
    tone: "positive",
    icon: /*#__PURE__*/React.createElement(Ico, {
      name: "truck",
      size: 20
    }),
    title: "Free delivery"
  }, "In stock. Order today, delivered by Thursday."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      marginTop: 24,
      gap: 16
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: 'var(--font-mono)',
      fontSize: 26,
      fontWeight: 600
    }
  }, "$", price.toLocaleString()), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: 'var(--ink-500)'
    }
  }, "or $", Math.round(price / 12), "/mo. for 12 mo.")), /*#__PURE__*/React.createElement(Button, {
    variant: "accent",
    size: "lg",
    iconLeft: /*#__PURE__*/React.createElement(Ico, {
      name: "shopping-bag",
      size: 18
    }),
    onClick: () => onAdd({
      id: p.id + '-' + size + '-' + finish + '-' + storage,
      name: p.name,
      finish: finishObj?.label,
      price,
      glyph: p.glyph,
      tone
    })
  }, "Add to bag")))));
}
function BagDrawer({
  open,
  items,
  onClose,
  onRemove
}) {
  const {
    Button
  } = window.AetherDesignSystem_7ccd51;
  const total = items.reduce((s, i) => s + i.price, 0);
  return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("div", {
    onClick: onClose,
    style: {
      position: 'fixed',
      inset: 0,
      zIndex: 60,
      background: 'rgba(20,22,26,0.32)',
      opacity: open ? 1 : 0,
      pointerEvents: open ? 'auto' : 'none',
      willChange: 'opacity',
      transition: 'opacity var(--dur-base) var(--ease-standard)'
    }
  }), /*#__PURE__*/React.createElement("aside", {
    style: {
      position: 'fixed',
      top: 0,
      right: 0,
      bottom: 0,
      width: 'min(420px, 92vw)',
      zIndex: 61,
      background: 'var(--surface)',
      boxShadow: 'var(--shadow-xl)',
      display: 'flex',
      flexDirection: 'column',
      transform: open ? 'translateX(0)' : 'translateX(100%)',
      willChange: 'transform',
      transition: 'transform var(--dur-slow) var(--ease-out)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '20px 24px',
      borderBottom: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: 19,
      fontWeight: 600
    }
  }, "Your bag"), /*#__PURE__*/React.createElement("button", {
    onClick: onClose,
    style: {
      ...iconBtn
    }
  }, /*#__PURE__*/React.createElement(Ico, {
    name: "x",
    size: 18
  }))), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      overflowY: 'auto',
      padding: 24,
      display: 'flex',
      flexDirection: 'column',
      gap: 14
    }
  }, items.length === 0 && /*#__PURE__*/React.createElement("div", {
    style: {
      textAlign: 'center',
      color: 'var(--ink-500)',
      marginTop: 48
    }
  }, /*#__PURE__*/React.createElement(Ico, {
    name: "shopping-bag",
    size: 32,
    color: "var(--ink-300)"
  }), /*#__PURE__*/React.createElement("p", {
    style: {
      marginTop: 12,
      fontSize: 14
    }
  }, "Your bag is empty.")), items.map(it => /*#__PURE__*/React.createElement("div", {
    key: it.id,
    style: {
      display: 'flex',
      gap: 14,
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: 'relative',
      width: 64,
      height: 64,
      borderRadius: 14,
      overflow: 'hidden',
      flex: 'none'
    }
  }, /*#__PURE__*/React.createElement(ProductMedia, {
    glyph: it.glyph,
    tone: it.tone,
    size: 32
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 600,
      fontSize: 15
    }
  }, it.name), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: 'var(--ink-500)'
    }
  }, it.finish)), /*#__PURE__*/React.createElement("div", {
    style: {
      textAlign: 'right'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: 'var(--font-mono)',
      fontSize: 14,
      fontWeight: 600
    }
  }, "$", it.price.toLocaleString()), /*#__PURE__*/React.createElement("button", {
    onClick: () => onRemove(it.id),
    style: {
      background: 'none',
      border: 'none',
      cursor: 'pointer',
      fontSize: 12,
      color: 'var(--accent-700)',
      padding: 0
    }
  }, "Remove"))))), items.length > 0 && /*#__PURE__*/React.createElement("div", {
    style: {
      padding: 24,
      borderTop: '1px solid var(--border-subtle)'
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      marginBottom: 16
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      color: 'var(--ink-500)'
    }
  }, "Total"), /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: 'var(--font-mono)',
      fontSize: 20,
      fontWeight: 600
    }
  }, "$", total.toLocaleString())), /*#__PURE__*/React.createElement(Button, {
    variant: "accent",
    size: "lg",
    fullWidth: true
  }, "Check out"))));
}
Object.assign(window, {
  ProductView,
  BagDrawer
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/storefront/product.jsx", error: String((e && e.message) || e) }); }

__ds_ns.Badge = __ds_scope.Badge;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.Card = __ds_scope.Card;

__ds_ns.Banner = __ds_scope.Banner;

__ds_ns.Input = __ds_scope.Input;

__ds_ns.Switch = __ds_scope.Switch;

__ds_ns.SegmentedControl = __ds_scope.SegmentedControl;

})();
