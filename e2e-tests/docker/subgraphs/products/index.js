import http from 'node:http';
import {buildSchema, graphql} from 'graphql';

const schema = buildSchema(`
  scalar DateTime
  scalar URL

  type Query {
    products: [Product!]!
    productById(id: ID!): Product
    productsSince(since: DateTime!): [Product!]!
  }

  type Product {
    id: ID!
    name: String!
    price: Int!
    createdAt: DateTime!
    imageUrl: URL!
  }
`);

const products = [
  { id: '1', name: 'Table', price: 899, createdAt: '2025-01-15T10:30:00Z', imageUrl: 'https://example.com/images/table.jpg' },
  { id: '2', name: 'Chair', price: 129, createdAt: '2025-03-20T14:00:00Z', imageUrl: 'https://example.com/images/chair.jpg' },
  { id: '3', name: 'Couch', price: 1299, createdAt: '2025-06-01T09:15:00Z', imageUrl: 'https://example.com/images/couch.jpg' },
];

const rootValue = {
  products: () => products,
  productById: ({ id }) => products.find((product) => product.id === id),
  productsSince: ({ since }) => products.filter((product) => product.createdAt >= since),
};

const server = http.createServer(async (request, response) => {
  if (request.method !== 'POST') {
    response.writeHead(405, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ errors: [{ message: 'Only POST is supported' }] }));
    return;
  }

  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }

  const body = chunks.length === 0 ? '{}' : Buffer.concat(chunks).toString('utf8');
  const { query, variables, operationName } = JSON.parse(body);

  const result = await graphql({
    schema,
    source: query,
    rootValue,
    variableValues: variables,
    operationName,
  });

  response.writeHead(200, { 'content-type': 'application/json' });
  response.end(JSON.stringify(result));
});

server.listen(4001, () => {
  console.log('Products subgraph ready at http://localhost:4001');
});
