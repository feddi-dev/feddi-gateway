import http from 'node:http';
import {buildSchema, GraphQLError, graphql} from 'graphql';

const schema = buildSchema(`
  scalar DateTime

  type Query {
    reviews: [Review!]!
    productById(id: ID!): Product
    # Error testing queries
    failingQuery: String
    reviewsByProductId(productId: ID!): [Review]
  }

  type Product {
    id: ID!
    reviews: [Review!]!
    # Field that fails for specific product IDs (e.g., "error-product")
    failingField: String
  }

  type Review {
    id: ID!
    productId: ID!
    text: String!
    stars: Int!
    writtenAt: DateTime!
  }
`);

const reviews = [
  { id: '101', productId: '1', text: 'Great table, very sturdy!', stars: 5, writtenAt: '2025-02-10T08:00:00Z' },
  { id: '102', productId: '1', text: 'Good quality for the price', stars: 4, writtenAt: '2025-02-15T12:30:00Z' },
  { id: '103', productId: '2', text: 'Comfortable chair', stars: 4, writtenAt: '2025-04-01T16:45:00Z' },
  { id: '104', productId: '3', text: 'Best couch ever!', stars: 5, writtenAt: '2025-07-10T11:00:00Z' },
  { id: '105', productId: '3', text: 'Very comfortable', stars: 5, writtenAt: '2025-07-12T09:20:00Z' },
];

const rootValue = {
  reviews: () => reviews,
  productById: ({ id }) => ({
    id,
    reviews: () => {
      if (id === 'error-reviews') {
        throw new GraphQLError('Failed to fetch reviews for this product', {
          extensions: { code: 'REVIEW_FETCH_ERROR' },
        });
      }
      return reviews.filter((review) => review.productId === id);
    },
    failingField: () => {
      if (id === 'error-product') {
        throw new GraphQLError('This field always fails for error-product', {
          extensions: { code: 'FIELD_ERROR' },
        });
      }
      return 'ok';
    },
  }),
  failingQuery: () => {
    throw new GraphQLError('This query always fails', {
      extensions: { code: 'ALWAYS_FAILS' },
    });
  },
  reviewsByProductId: ({ productId }) => {
    if (productId === 'error') {
      throw new GraphQLError('Failed to fetch reviews: product not found', {
        extensions: { code: 'PRODUCT_NOT_FOUND' },
      });
    }
    if (productId === 'timeout') {
      throw new GraphQLError('Review service timeout', {
        extensions: { code: 'TIMEOUT' },
      });
    }
    return reviews.filter((review) => review.productId === productId);
  },
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

server.listen(4002, () => {
  console.log('Reviews subgraph ready at http://localhost:4002');
});
