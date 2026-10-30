import store from '../store';
const entries = values => Array.isArray(values) ? values.map(value => [value, value]) : Object.entries(values);
export function mapState(values) {
  return Object.fromEntries(entries(values).map(([key, value]) => [key, function () {
    return typeof value === 'function' ? value.call(this, store.state, store.getters) : store.state[value];
  }]));
}
export function mapGetters(values) { return Object.fromEntries(entries(values).map(([key, value]) => [key, () => store.getters[value]])); }
export function mapActions(values) { return Object.fromEntries(entries(values).map(([key, value]) => [key, payload => store.dispatch(value, payload)])); }
export function mapMutations(values) { return Object.fromEntries(entries(values).map(([key, value]) => [key, payload => store.commit(value, payload)])); }
