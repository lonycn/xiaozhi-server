import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const addressBookSource = await readFile(
  new URL('../src/views/AddressBookManagement.vue', import.meta.url),
  'utf8',
);
const correctWordApiSource = await readFile(
  new URL('../src/apis/module/correctWord.js', import.meta.url),
  'utf8',
);
const functionDialogSource = await readFile(
  new URL('../src/components/FunctionDialog.vue', import.meta.url),
  'utf8',
);
const modelConfigSource = await readFile(
  new URL('../src/views/ModelConfig.vue', import.meta.url),
  'utf8',
);

test('address-book permission state consistently uses the target device MAC', () => {
  assert.match(
    addressBookSource,
    /:value="selectedPermissions\.includes\(device\.deviceId\)"/,
  );
  assert.match(
    addressBookSource,
    /@change="\(checked\) => handlePermissionToggle\(device\.deviceId, checked\)"/,
  );
  assert.match(
    addressBookSource,
    /this\.selectedPermissions = this\.allDevices\.map\(d => d\.deviceId\)/,
  );
  assert.match(
    addressBookSource,
    /this\.originalPermissions\.includes\(device\.deviceId\)/,
  );
  assert.doesNotMatch(
    addressBookSource,
    /selectedPermissions\.includes\(device\.id\)/,
  );
  assert.doesNotMatch(
    addressBookSource,
    /originalPermissions\.includes\(device\.id\)/,
  );
  assert.match(
    addressBookSource,
    /requestId !== this\.permissionRequestSequence/,
  );
  assert.match(
    addressBookSource,
    /this\.selectedDevice\?\.deviceId !== macAddress/,
  );
  assert.match(
    addressBookSource,
    /this\.permissionsLoading = true;\s*this\.selectedPermissions = \[\];\s*this\.originalPermissions = \[\];/,
  );
  assert.match(
    addressBookSource,
    /handleSavePermissions\(\) \{\s*if \(this\.permissionsLoading\) return;/,
  );
});

test('correct-word pagination maps the UI page size to the backend limit query', () => {
  assert.match(
    correctWordApiSource,
    /new URLSearchParams\(\{\s*page: params\.page,\s*limit: params\.pageSize\s*\}\)/,
  );
  assert.doesNotMatch(correctWordApiSource, /pageSize: params\.pageSize/);
});

test('model navigation keeps its scrollable layout declarations', () => {
  const navStyles = modelConfigSource.match(/\.nav-panel\s*\{([^}]*)\}/)?.[1];
  const itemStyles = modelConfigSource.match(
    /\.nav-panel\s+\.el-menu-item\s*\{([^}]*)\}/,
  )?.[1];

  assert.ok(navStyles, 'model navigation should define its layout');
  assert.ok(itemStyles, 'model menu items should define their layout');
  const wrapperStyles = modelConfigSource.match(/\.main-wrapper\s*\{([^}]*)\}/)?.[1];
  assert.ok(wrapperStyles, 'model page wrapper should define its height');

  assert.match(navStyles, /box-sizing:\s*border-box;/);
  assert.match(navStyles, /overflow-y:\s*auto;/);
  assert.match(navStyles, /display:\s*flex;/);
  assert.match(navStyles, /flex-direction:\s*column;/);
  assert.match(itemStyles, /flex-shrink:\s*0;/);
  assert.match(wrapperStyles, /flex:\s*1;/);
  assert.match(wrapperStyles, /min-height:\s*0;/);
  assert.doesNotMatch(wrapperStyles, /height:\s*calc\([^)]*vh[^)]*\)/);

  // Base scrolling rules stay intact; responsive width and color overrides
  // belong to the shared admin layout and are intentionally allowed.
  assert.equal((modelConfigSource.match(/^\.nav-panel\s*\{/gm) || []).length, 1);
  assert.equal((modelConfigSource.match(/^\.nav-panel\s+\.el-menu-item\s*\{/gm) || []).length, 1);
  assert.doesNotMatch(modelConfigSource, /\.nav-panel[^{}]*\{[^}]*overflow(?:-y)?:\s*hidden/);

});

test('function dialog footer stays above the expanding MCP tools section', () => {
  const mcpLayer = functionDialogSource.match(
    /\.mcp-access-point\s*\{[^}]*z-index:\s*(\d+);/s,
  );
  const footerLayer = functionDialogSource.match(
    /\.drawer-footer\s*\{[^}]*z-index:\s*(\d+);/s,
  );

  assert.ok(mcpLayer, 'MCP section should define its stacking layer');
  assert.ok(footerLayer, 'drawer footer should define its stacking layer');
  assert.ok(Number(footerLayer[1]) > Number(mcpLayer[1]));
});
