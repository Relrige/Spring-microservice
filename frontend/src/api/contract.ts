// Helpers over the types generated from the services' OpenAPI documents
// (change api-contract-types, design D3). `Op` is one entry of a generated
// `operations` interface, e.g. `operations['browseProducts']`.

type SuccessStatus = 200 | 201 | 202 | 204;

type JsonOf<T> = T extends { content: { 'application/json': infer B } } ? B : never;

/** Request body of an operation (`application/json`). */
export type JsonBody<Op> = Op extends { requestBody?: infer R } ? JsonOf<NonNullable<R>> : never;

/** Query parameters of an operation. */
export type Query<Op> = Op extends { parameters: { query?: infer Q } } ? NonNullable<Q> : never;

/** Header parameters of an operation. */
export type HeaderParams<Op> = Op extends { parameters: { header?: infer H } }
  ? NonNullable<H>
  : never;

/** Path parameters of an operation. */
export type PathParams<Op> = Op extends { parameters: { path?: infer P } } ? NonNullable<P> : never;

type SuccessResponse<Op> = Op extends { responses: infer R } ? R[SuccessStatus & keyof R] : never;

/** Success body of an operation; `void` when its answer has no content (`204`). */
export type Ok<Op> =
  SuccessResponse<Op> extends { content: { 'application/json': infer B } } ? B : void;

type OptionalKeys<T> = {
  [K in keyof T]-?: object extends Pick<T, K> ? K : never;
}[keyof T];

type Flatten<T> = { [K in keyof T]: T[K] };

/**
 * An answer as it can arrive: the documents mark a property required only when
 * it is never missing and never `null`, and never encode `null` (conventions
 * D5). Every optional property may therefore also be `null` (spec api-access,
 * Missing and null fields are treated alike).
 */
export type Received<T> = T extends readonly (infer U)[]
  ? Received<U>[]
  : T extends object
    ? string extends keyof T
      ? T
      : Flatten<
          { [K in Exclude<keyof T, OptionalKeys<T>>]: Received<T[K]> } & {
            [K in OptionalKeys<T>]?: Received<Exclude<T[K], undefined>> | null;
          }
        >
    : T;

/** The one paging envelope of every list (BL-05): `page` zero-based, `size` the effective page size. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

type TemplateParams<T extends string> = T extends `${string}{${infer P}}${infer Rest}`
  ? P | TemplateParams<Rest>
  : never;

type PathArgs<T extends string> = [TemplateParams<T>] extends [never]
  ? []
  : [params: Record<TemplateParams<T>, string | number>];

/** Fills `{name}` placeholders with URL-encoded values. */
export function fillPath(template: string, params: Record<string, string | number> = {}): string {
  return template.replace(/\{([^}]+)\}/g, (_, name: string) => {
    const value = params[name];
    if (value === undefined) throw new Error(`Missing path parameter "${name}" for ${template}`);
    return encodeURIComponent(String(value));
  });
}

/**
 * A path builder for one service: `pathFor('/catalog/products/{id}', { id })`.
 * The template must be a path of that service's document, or it does not compile.
 */
export function pathsOf<Paths>() {
  return <T extends keyof Paths & string>(template: T, ...args: PathArgs<T>): string =>
    fillPath(template, args[0]);
}
