FROM node:22-alpine
WORKDIR /app
COPY package.json server.mjs ./
ENV HOST=0.0.0.0 PORT=8788 POCKET_PUSH_STATE=/data
USER node
EXPOSE 8788
CMD ["node", "server.mjs"]
