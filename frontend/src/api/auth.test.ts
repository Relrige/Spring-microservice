import { describe, expectTypeOf, it } from 'vitest';
import type { CreatedUser, Credentials, LoginResponse, Role } from './auth';
import type { components } from './generated/auth';

type Schemas = components['schemas'];

describe('auth contract', () => {
  it('Role equals the generated role enum', () => {
    expectTypeOf<Role>().toEqualTypeOf<Schemas['CreateNonCustomerUserRequest']['role']>();
  });

  it('uses the generated request and response shapes', () => {
    expectTypeOf<Credentials>().toEqualTypeOf<Schemas['RegisterUserRequest']>();
    expectTypeOf<LoginResponse>().toEqualTypeOf<Schemas['LoginResponse']>();
    expectTypeOf<CreatedUser>().toEqualTypeOf<Schemas['RegisterUserResponse']>();
  });
});
