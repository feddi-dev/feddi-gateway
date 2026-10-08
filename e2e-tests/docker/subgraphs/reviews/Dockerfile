FROM node:25-alpine

WORKDIR /app

COPY package.json ./
RUN npm install

COPY index.js ./

EXPOSE 4002

CMD ["npm", "start"]
