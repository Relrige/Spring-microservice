import { Button, Group, TextInput } from '@mantine/core';
import { useState } from 'react';

interface StaffSearchProps {
  initial: string;
  label: string;
  placeholder: string;
  onSearch: (search: string) => void;
}

/** Search submitted with Enter or the button, as on the storefront (design D5). */
export function StaffSearch({ initial, label, placeholder, onSearch }: StaffSearchProps) {
  const [value, setValue] = useState(initial);
  return (
    <form
      role="search"
      style={{ flex: '1 1 260px', minWidth: 0 }}
      onSubmit={(event) => {
        event.preventDefault();
        onSearch(value.trim());
      }}
    >
      <Group gap="sm" wrap="nowrap" align="flex-end">
        <TextInput
          type="search"
          label={label}
          placeholder={placeholder}
          value={value}
          onChange={(event) => setValue(event.currentTarget.value)}
          style={{ flex: '1 1 auto', minWidth: 0 }}
        />
        <Button type="submit" variant="default">
          Search
        </Button>
      </Group>
    </form>
  );
}
